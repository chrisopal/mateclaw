package vip.mate.semantic;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import vip.mate.semantic.statement.SemanticDomainMapper;
import vip.mate.semantic.support.SemanticHttpFixture;

import java.time.LocalDateTime;
import java.util.*;

class SemanticOntologyImpactIntegrationTest extends SemanticHttpFixture {
    @MockitoSpyBean SemanticDomainMapper domain;

    @Test
    void concurrentGraphOrDraftMutationCannotProduceFreshReport() throws Exception {
        String ontology = create();
        var d = draft(ontology);
        var saved =
                call(
                        "PUT",
                        "/ontologies/" + ontology + "/draft",
                        "member",
                        workspace,
                        saveBody(d.path("draftVersion").asLong(), definition(false, "MULTI")),
                        200);
        var v1 = publish(ontology, saved.path("draftVersion").asLong(), op());
        var g = graph(v1.path("id").asText());
        var target =
                call(
                        "POST",
                        "/ontologies/" + ontology + "/draft",
                        "member",
                        workspace,
                        Map.of("baseRevisionId", v1.path("id").asText()),
                        200);
        for (boolean changeGraph : List.of(true, false)) {
            doAnswer(
                            invocation -> {
                                var result = invocation.callRealMethod();
                                java.util.concurrent.CompletableFuture.runAsync(
                                                () -> {
                                                    if (changeGraph)
                                                        jdbc.update(
                                                                "UPDATE mate_semantic_graph SET"
                                                                    + " mutation_version=mutation_version+1"
                                                                    + " WHERE id=?",
                                                                g.graph());
                                                    else
                                                        jdbc.update(
                                                                "UPDATE"
                                                                    + " mate_semantic_ontology_revision"
                                                                    + " SET draft_version=draft_version+1"
                                                                    + " WHERE id=?",
                                                                target.path("id").asText());
                                                })
                                        .get(3, java.util.concurrent.TimeUnit.SECONDS);
                                return result;
                            })
                    .when(domain)
                    .entities(any());
            try {
                var result =
                        call(
                                "POST",
                                "/ontologies/" + ontology + "/impact",
                                "owner",
                                workspace,
                                Map.of(
                                        "graphId",
                                        g.graph(),
                                        "expectedDraftVersion",
                                        target.path("draftVersion").asLong()),
                                409);
                assertEquals("IMPACT_STALE", result.path("code").asText());
            } finally {
                reset(domain);
            }
        }
    }

    @Test
    void fullCountsRemainAccurateWhenDetailsAreTruncatedAndBudgetExceededFailsClosed()
            throws Exception {
        String ontology = create();
        var d = draft(ontology);
        var saved =
                call(
                        "PUT",
                        "/ontologies/" + ontology + "/draft",
                        "member",
                        workspace,
                        saveBody(d.path("draftVersion").asLong(), definition(false, "MULTI")),
                        200);
        var v1 = publish(ontology, saved.path("draftVersion").asLong(), op());
        var g = graph(v1.path("id").asText());
        var first = propose(g, "0.08", "UNKNOWN", null, null, 200);
        var draft2 =
                call(
                        "POST",
                        "/ontologies/" + ontology + "/draft",
                        "member",
                        workspace,
                        Map.of("baseRevisionId", v1.path("id").asText()),
                        200);
        var target =
                call(
                        "PUT",
                        "/ontologies/" + ontology + "/draft",
                        "member",
                        workspace,
                        saveBody(draft2.path("draftVersion").asLong(), definition(true, "SINGLE")),
                        200);
        var params =
                Map.of(
                        "graphId",
                        g.graph(),
                        "expectedDraftVersion",
                        target.path("draftVersion").asLong());
        seedCopies(g, first.path("id").asText(), 204);
        var result =
                call(
                        "POST",
                        "/ontologies/" + ontology + "/impact",
                        "owner",
                        workspace,
                        params,
                        200);
        assertEquals(205, result.path("scannedStatements").asInt());
        assertEquals(205, result.path("affectedStatements").asInt());
        assertEquals(200, result.path("diagnostics").size());
        assertTrue(result.path("detailsTruncated").asBoolean());
        seedCopies(g, first.path("id").asText(), 9795);
        long started = System.nanoTime();
        var full =
                call(
                        "POST",
                        "/ontologies/" + ontology + "/impact",
                        "owner",
                        workspace,
                        params,
                        200);
        System.out.println(
                "M4 impact 10000 policy violations ms=" + (System.nanoTime() - started) / 1_000_000);
        assertEquals(10000, full.path("affectedStatements").asInt());
        seedCopies(g, first.path("id").asText(), 1);
        assertEquals(
                "IMPACT_INCOMPLETE",
                call("POST", "/ontologies/" + ontology + "/impact", "owner", workspace, params, 504)
                        .path("code")
                        .asText());
    }

    @Test
    void tenThousandPositiveRowsHandleEqualDenseAndDisjointValues() throws Exception {
        String ontology = create();
        var d = draft(ontology);
        var saved =
                call(
                        "PUT",
                        "/ontologies/" + ontology + "/draft",
                        "member",
                        workspace,
                        saveBody(d.path("draftVersion").asLong(), definition(false, "MULTI")),
                        200);
        var v1 = publish(ontology, saved.path("draftVersion").asLong(), op());
        var g = graph(v1.path("id").asText());
        var first = propose(g, "0.03", "INTERVAL", null, null, 200);
        seedCopies(g, first.path("id").asText(), 9999);
        var d2 =
                call(
                        "POST",
                        "/ontologies/" + ontology + "/draft",
                        "member",
                        workspace,
                        Map.of("baseRevisionId", v1.path("id").asText()),
                        200);
        var target =
                call(
                        "PUT",
                        "/ontologies/" + ontology + "/draft",
                        "member",
                        workspace,
                        saveBody(d2.path("draftVersion").asLong(), definition(false, "SINGLE")),
                        200);
        var params =
                Map.of(
                        "graphId",
                        g.graph(),
                        "expectedDraftVersion",
                        target.path("draftVersion").asLong());
        var ids =
                jdbc.queryForList(
                        "SELECT id FROM mate_semantic_statement WHERE graph_id=? ORDER BY id",
                        String.class,
                        g.graph());
        for (String distribution : List.of("equal", "dense", "disjoint")) {
            if (!distribution.equals("equal")) {
                var batch = new ArrayList<Object[]>();
                for (int i = 0; i < ids.size(); i++) {
                    String from = null, to = null;
                    if (distribution.equals("disjoint")) {
                        from = java.time.Instant.EPOCH.plusSeconds(i * 2L).toString();
                        to = java.time.Instant.EPOCH.plusSeconds(i * 2L + 1).toString();
                    }
                    batch.add(
                            new Object[] {
                                json.writeValueAsString(
                                        payload(
                                                g,
                                                i % 2 == 0 ? "0.03" : "0.04",
                                                "INTERVAL",
                                                from,
                                                to)),
                                ids.get(i)
                            });
                }
                jdbc.batchUpdate(
                        "UPDATE mate_semantic_statement_revision SET content_json=? WHERE"
                                + " statement_id=? AND revision=1",
                        batch);
            }
            long started = System.nanoTime();
            var result =
                    call(
                            "POST",
                            "/ontologies/" + ontology + "/impact",
                            "owner",
                            workspace,
                            params,
                            200);
            System.out.println(
                    "M4 impact 10000 "
                            + distribution
                            + " ms="
                            + (System.nanoTime() - started) / 1_000_000);
            assertEquals(10000, result.path("scannedStatements").asInt());
            assertEquals(
                    0,
                    result.path("affectedStatements").asInt());
        }
    }

    @Test
    void positiveMultiValuesDoNotBecomeConflictsForUnknownOrOverlappingIntervals() throws Exception {
        String ontology = create();
        var d = draft(ontology);
        var saved =
                call(
                        "PUT",
                        "/ontologies/" + ontology + "/draft",
                        "member",
                        workspace,
                        saveBody(d.path("draftVersion").asLong(), definition(false, "MULTI")),
                        200);
        var source = publish(ontology, saved.path("draftVersion").asLong(), op());
        var g = graph(source.path("id").asText());
        var rows = new ArrayList<Map<String, Object>>();
        var random = new Random(42);
        for (int i = 0; i < 60; i++) {
            boolean unknown = i % 11 == 0;
            int start = random.nextInt(30);
            int end = start + 1 + random.nextInt(4);
            var payload =
                    payload(
                            g,
                            String.valueOf(random.nextInt(3)),
                            unknown ? "UNKNOWN" : "INTERVAL",
                            unknown || i % 7 == 0
                                    ? null
                                    : java.time.Instant.EPOCH.plusSeconds(start).toString(),
                            unknown || i % 5 == 0
                                    ? null
                                    : java.time.Instant.EPOCH.plusSeconds(end).toString());
            call("POST", "/graphs/" + g.graph() + "/statements", "member", workspace, payload, 200);
            rows.add(payload);
        }
        int affected = 0; // All generated assertions are positive; distinct values are allowed.
        var next =
                call(
                        "POST",
                        "/ontologies/" + ontology + "/draft",
                        "member",
                        workspace,
                        Map.of("baseRevisionId", source.path("id").asText()),
                        200);
        var target =
                call(
                        "PUT",
                        "/ontologies/" + ontology + "/draft",
                        "member",
                        workspace,
                        saveBody(next.path("draftVersion").asLong(), definition(false, "SINGLE")),
                        200);
        var result =
                call(
                        "POST",
                        "/ontologies/" + ontology + "/impact",
                        "owner",
                        workspace,
                        Map.of(
                                "graphId",
                                g.graph(),
                                "expectedDraftVersion",
                                target.path("draftVersion").asLong()),
                        200);
        assertEquals(affected, result.path("affectedStatements").asInt());
    }

    private void seedCopies(Graph graph, String statement, int count) {
        List<Object[]> statements = new ArrayList<>(), revisions = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String next = id();
            statements.add(new Object[] {next, statement});
            revisions.add(new Object[] {next, statement});
        }
        jdbc.batchUpdate(
                "INSERT INTO"
                    + " mate_semantic_statement(id,graph_id,current_revision,created_by,created_at)"
                    + " SELECT ?,graph_id,current_revision,created_by,created_at FROM"
                    + " mate_semantic_statement WHERE id=?",
                statements);
        jdbc.batchUpdate(
                "INSERT INTO"
                    + " mate_semantic_statement_revision(statement_id,revision,graph_id,ontology_revision_id,subject_id,predicate_kind,predicate_key,predicate_iri,assertion_kind,assertion_text,review_status,validity_kind,valid_from,valid_to,value_type,value_text,unit,target_entity_id,content_json,actor_id,reason,created_at)"
                    + " SELECT"
                    + " ?,revision,graph_id,ontology_revision_id,subject_id,predicate_kind,predicate_key,predicate_iri,assertion_kind,assertion_text,review_status,validity_kind,valid_from,valid_to,value_type,value_text,unit,target_entity_id,content_json,actor_id,reason,created_at"
                    + " FROM mate_semantic_statement_revision WHERE statement_id=? AND revision=1",
                revisions);
    }

    @Test
    void inspectionCodePolicyImpactIsReadOnlyAndOnlyNewBindingsUseNewPolicy() throws Exception {
        String ontology = create();
        var d = draft(ontology);
        var saved =
                call(
                        "PUT",
                        "/ontologies/" + ontology + "/draft",
                        "member",
                        workspace,
                        saveBody(d.path("draftVersion").asLong(), definition(false, "SINGLE")),
                        200);
        var v1 = publish(ontology, saved.path("draftVersion").asLong(), op());
        var old = graph(v1.path("id").asText());
        var fact = propose(old, "0.08", "INTERVAL", null, null, 200);
        review(old, fact);
        var draft2 =
                call(
                        "POST",
                        "/ontologies/" + ontology + "/draft",
                        "member",
                        workspace,
                        Map.of("baseRevisionId", v1.path("id").asText()),
                        200);
        var newDraft =
                call(
                        "PUT",
                        "/ontologies/" + ontology + "/draft",
                        "member",
                        workspace,
                        saveBody(draft2.path("draftVersion").asLong(), definition(true, "SINGLE")),
                        200);
        long version = graphVersion(old.graph());
        var result =
                call(
                        "POST",
                        "/ontologies/" + ontology + "/impact",
                        "owner",
                        workspace,
                        Map.of(
                                "graphId",
                                old.graph(),
                                "expectedDraftVersion",
                                newDraft.path("draftVersion").asLong()),
                        200);
        assertEquals("REQUIRES_REVIEW", result.path("definitionChangeClass").asText());
        assertEquals("VIOLATIONS", result.path("dataConformance").asText());
        assertEquals(1, result.path("affectedStatements").asInt());
        assertEquals(
                fact.path("id").asText(), result.path("diagnostics").get(0).path("id").asText());
        assertEquals(version, graphVersion(old.graph()));
        assertEquals(v1.path("id").asText(), result.path("sourceRevisionId").asText());
        var v2 = publish(ontology, newDraft.path("draftVersion").asLong(), op());
        assertEquals(
                v1.path("id").asText(),
                call("GET", "/graphs/" + old.graph(), "viewer", workspace, null, 200)
                        .path("binding")
                        .path("ontologyRevisionId")
                        .asText());
        propose(old, "0.09", "INTERVAL", null, null, 200);
        var fresh = graph(v2.path("id").asText());
        propose(fresh, "0.08", "INTERVAL", null, null, 422);
        var valid = propose(fresh, "0.03", "INTERVAL", null, null, 200);
        call(
                "POST",
                "/graphs/"
                        + fresh.graph()
                        + "/statements/"
                        + valid.path("id").asText()
                        + "/changes",
                "member",
                workspace,
                Map.of(
                        "operationId",
                        op(),
                        "expectedRevision",
                        1,
                        "content",
                        payload(fresh, "0.08", "INTERVAL", null, null)),
                422);
        review(fresh, valid);
        var search =
                call(
                        "POST",
                        "/graphs/" + fresh.graph() + "/search",
                        "viewer",
                        workspace,
                        Map.of("query", "检验编码"),
                        200);
        assertEquals(1, search.path("facts").size());
        assertEquals("检验编码", search.path("predicateLabels").path("urn:test:inspectionCode").asText());
        var status = new LinkedHashMap<>(payload(fresh, "0.03", "INTERVAL", null, null));
        status.put("assertionText","DataPropertyAssertion(<urn:test:investigationStatus> <urn:test:issue:"+fresh.graph()+"> \"unknown\")");
        call("POST", "/graphs/" + fresh.graph() + "/statements", "member", workspace, status, 422);
        call(
                "PUT",
                "/knowledge-bases/" + old.kb() + "/binding",
                "owner",
                workspace,
                Map.of(
                        "action",
                        "REBIND",
                        "revisionId",
                        v2.path("id").asText(),
                        "expectedGraphVersion",
                        graphVersion(old.graph())),
                409);
        var usage =
                call("GET", "/ontologies/" + ontology + "/usage", "viewer", workspace, null, 200);
        assertEquals(2, usage.path("total").asInt());
        assertTrue(usage.path("items").get(0).path("graphVersion").isNumber());
        call(
                "POST",
                "/ontologies/" + ontology + "/impact",
                "member",
                workspace,
                Map.of("graphId", old.graph(), "targetRevisionId", v2.path("id").asText()),
                403);
        call(
                "POST",
                "/ontologies/" + ontology + "/impact",
                "owner",
                otherWorkspace,
                Map.of("graphId", old.graph(), "targetRevisionId", v2.path("id").asText()),
                404);
        jdbc.update(
                "UPDATE mate_wiki_knowledge_base SET workspace_id=? WHERE id=?",
                Long.valueOf(otherWorkspace),
                Long.valueOf(old.kb()));
        assertEquals(
                1,
                call("GET", "/ontologies/" + ontology + "/usage", "viewer", workspace, null, 200)
                        .path("total")
                        .asInt());
        call(
                "POST",
                "/ontologies/" + ontology + "/impact",
                "owner",
                workspace,
                Map.of("graphId", old.graph(), "targetRevisionId", v2.path("id").asText()),
                404);
    }

    @Test
    void impactIncludesUnsupportedAcceptedFactsCandidatesAndPendingChanges() throws Exception {
        String ontology = create();
        var d = draft(ontology);
        var saved =
                call(
                        "PUT",
                        "/ontologies/" + ontology + "/draft",
                        "member",
                        workspace,
                        saveBody(d.path("draftVersion").asLong(), definition(false, "MULTI")),
                        200);
        var v1 = publish(ontology, saved.path("draftVersion").asLong(), op());
        var graph = graph(v1.path("id").asText());
        var first = propose(graph, "0.08", "UNKNOWN", null, null, 200);
        review(graph, first);
        var second = propose(graph, "0.09", "INTERVAL", null, null, 200);
        review(graph, second);
        propose(graph,"0.09","INTERVAL",null,null,200);
        var proposal =
                call(
                        "POST",
                        "/graphs/"
                                + graph.graph()
                                + "/statements/"
                                + first.path("id").asText()
                                + "/changes",
                        "member",
                        workspace,
                        Map.of(
                                "operationId",
                                op(),
                                "expectedRevision",
                                2,
                                "content",
                                payload(graph, "0.08", "UNKNOWN", null, null)),
                        200);
        call(
                "POST",
                "/graphs/" + graph.graph() + "/sources/withdraw",
                "owner",
                workspace,
                Map.of(
                        "sourceKind",
                        "WIKI_RAW",
                        "sourceRef",
                        graph.raw(),
                        "reason",
                        "fixture withdrawn",
                        "operationId",
                        op()),
                200);
        assertEquals(
                0,
                call(
                                "GET",
                                "/graphs/" + graph.graph() + "/statements?view=trusted",
                                "viewer",
                                workspace,
                                null,
                                200)
                        .path("total")
                        .asInt());
        var draft2 =
                call(
                        "POST",
                        "/ontologies/" + ontology + "/draft",
                        "member",
                        workspace,
                        Map.of("baseRevisionId", v1.path("id").asText()),
                        200);
        var target =
                call(
                        "PUT",
                        "/ontologies/" + ontology + "/draft",
                        "member",
                        workspace,
                        saveBody(draft2.path("draftVersion").asLong(), definition(true, "SINGLE")),
                        200);
        var result =
                call(
                        "POST",
                        "/ontologies/" + ontology + "/impact",
                        "owner",
                        workspace,
                        Map.of(
                                "graphId",
                                graph.graph(),
                                "expectedDraftVersion",
                                target.path("draftVersion").asLong()),
                        200);
        assertEquals(3,result.path("scannedStatements").asInt());
        assertEquals(3, result.path("affectedStatements").asInt());
        assertEquals(1, result.path("affectedProposals").asInt());
        assertTrue(result.path("diagnostics").toString().contains(proposal.path("id").asText()));
        call(
                "POST",
                "/ontologies/" + ontology + "/impact",
                "owner",
                workspace,
                Map.of(
                        "graphId",
                        graph.graph(),
                        "expectedDraftVersion",
                        target.path("draftVersion").asLong() - 1),
                409);
    }

    @Test void explicitOppositeAssertionsAppearInImpactButDifferentPositiveValuesDoNot() throws Exception {
        String ontology=create(); var d=draft(ontology);
        var saved=call("PUT","/ontologies/"+ontology+"/draft","member",workspace,saveBody(d.path("draftVersion").asLong(),definition(false,"MULTI")),200);
        var published=publish(ontology,saved.path("draftVersion").asLong(),op());
        var graph=graph(published.path("id").asText());
        var positive=propose(graph,"0.03","INTERVAL",null,null,200);
        var negative=payload(graph,"0.03","INTERVAL",null,null);
        negative.put("assertionText",negative.get("assertionText").toString().replace("DataPropertyAssertion(","NegativeDataPropertyAssertion("));
        var opposite=call("POST","/graphs/"+graph.graph()+"/statements","member",workspace,negative,200);
        var independent=propose(graph,"0.04","INTERVAL",null,null,200);
        var report=call("POST","/ontologies/"+ontology+"/impact","owner",workspace,
                Map.of("graphId",graph.graph(),"targetRevisionId",published.path("id").asText()),200);
        assertEquals(2,report.path("affectedStatements").asInt());
        assertTrue(report.path("diagnostics").toString().contains(positive.path("id").asText()));
        assertTrue(report.path("diagnostics").toString().contains(opposite.path("id").asText()));
        assertFalse(report.path("diagnostics").toString().contains(independent.path("id").asText()));
        assertEquals("UNCHANGED",report.path("definitionChangeClass").asText());
    }

    @Test void explicitBusinessSingleValuePolicyCreatesReviewableConflict() throws Exception {
        String ontology=create();var d=draft(ontology);var input=definition(false,"MULTI");
        input.put("policy",Map.of("version","single-v1","rules",List.of(Map.of(
                "classIri","urn:test:QualityIssue","predicateIri","urn:test:inspectionCode","required",false,
                "allowedLexicalValues",List.of(),"singleValue",true))));
        var saved=call("PUT","/ontologies/"+ontology+"/draft","member",workspace,saveBody(d.path("draftVersion").asLong(),input),200);
        var published=publish(ontology,saved.path("draftVersion").asLong(),op());var graph=graph(published.path("id").asText());
        var first=propose(graph,"0.03","INTERVAL",null,null,200);
        var second=propose(graph,"0.04","INTERVAL",null,null,200);
        var conflicts=call("GET","/graphs/"+graph.graph()+"/conflicts","owner",workspace,null,200).path("items");
        assertEquals(1,conflicts.size());assertEquals("BUSINESS_SINGLE_VALUE",conflicts.get(0).path("kind").asText());
        var report=call("POST","/ontologies/"+ontology+"/impact","owner",workspace,
                Map.of("graphId",graph.graph(),"targetRevisionId",published.path("id").asText()),200);
        assertEquals(2,report.path("affectedStatements").asInt());
        call("POST","/graphs/"+graph.graph()+"/statements/"+first.path("id").asText()+"/review","owner",workspace,
                Map.of("expectedRevision",1,"action","ACCEPT","reason","must review conflict","operationId",op()),409);
        call("POST","/graphs/"+graph.graph()+"/conflicts/"+conflicts.get(0).path("id").asText()+"/resolve","owner",workspace,
                Map.of("winnerStatementId",first.path("id").asText(),"expectedMembers",List.of(
                    Map.of("statementId",first.path("id").asText(),"revision",1),Map.of("statementId",second.path("id").asText(),"revision",1)),
                    "reason","Explicit policy verified","operationId",op()),200);
    }

    private Map<String, Object> definition(boolean enhanced, String multiplicity) {
        var result=new LinkedHashMap<String,Object>(owlDocument("""
            Ontology(<urn:test:inspection>
              Declaration(Class(<urn:test:QualityIssue>))
              Declaration(DataProperty(<urn:test:inspectionCode>))
              DataPropertyRange(<urn:test:inspectionCode> <http://www.w3.org/2001/XMLSchema#decimal>)
              Declaration(DataProperty(<urn:test:investigationStatus>))
              AnnotationAssertion(<http://www.w3.org/2000/01/rdf-schema#label> <urn:test:inspectionCode> "检验编码")
              AnnotationAssertion(<http://www.w3.org/2000/01/rdf-schema#comment> <urn:test:inspectionCode> "测量编码"))
            """));
        if (enhanced) result.put("policy",Map.of("version","codes-v2","rules",List.of(
                Map.of("classIri","urn:test:QualityIssue","predicateIri","urn:test:inspectionCode","required",false,"allowedLexicalValues",List.of("0.03","0.04")),
                Map.of("classIri","urn:test:QualityIssue","predicateIri","urn:test:investigationStatus","required",false,"allowedLexicalValues",List.of("待复核","已确认")))));
        return result;
    }

    private Graph graph(String revision) throws Exception {
        String kb = id();
        var now = LocalDateTime.now();
        jdbc.update(
                "INSERT INTO"
                    + " mate_wiki_knowledge_base(id,name,description,status,page_count,raw_count,workspace_id,create_time,update_time,deleted)"
                    + " VALUES(?,?,?,?,?,?,?,?,?,0)",
                Long.valueOf(kb),
                "质量验收",
                "",
                "active",
                0,
                0,
                Long.valueOf(workspace),
                now,
                now);
        String graph =
                call(
                                "PUT",
                                "/knowledge-bases/" + kb + "/binding",
                                "owner",
                                workspace,
                                Map.of("action", "ENABLE", "revisionId", revision),
                                200)
                        .path("graphId")
                        .asText();
        String entity =
                call(
                                "POST",
                                "/graphs/" + graph + "/entities",
                                "member",
                                workspace,
                                Map.of("iri","urn:test:issue:"+graph,"assertedTypes",Set.of("urn:test:QualityIssue"),"displayName","检验记录"),
                                200)
                        .path("id")
                        .asText();
        String raw = id(), text = "合成检验🔎：样本偏差为 0.03、0.04、0.08、0.09 mm，待复核。";
        jdbc.update(
                "INSERT INTO"
                    + " mate_wiki_raw_material(id,kb_id,title,source_type,original_content,file_size,processing_status,create_time,update_time,deleted)"
                    + " VALUES(?,?,?,?,?,?,?,?,?,0)",
                Long.valueOf(raw),
                Long.valueOf(kb),
                "质量检验",
                "text",
                text,
                text.length(),
                "completed",
                now,
                now);
        String snapshot =
                call(
                                "POST",
                                "/graphs/" + graph + "/imports",
                                "member",
                                workspace,
                                Map.of(
                                        "sourceKind",
                                        "WIKI_RAW",
                                        "sourceRef",
                                        raw,
                                        "operationId",
                                        op()),
                                200)
                        .path("snapshotId")
                        .asText();
        String evidence =
                call(
                                "POST",
                                "/graphs/" + graph + "/snapshots/" + snapshot + "/evidence",
                                "member",
                                workspace,
                                Map.of(
                                        "operationId",
                                        op(),
                                        "startCodePoint",
                                        0,
                                        "endCodePoint",
                                        text.codePointCount(0, text.length()),
                                        "exactQuote",
                                        text),
                                200)
                        .path("id")
                        .asText();
        return new Graph(kb, graph, entity, raw, evidence);
    }

    private Map<String, Object> payload(
            Graph g, String value, String validity, String from, String to) {
        var r =
                new LinkedHashMap<String, Object>(
                        Map.of(
                                "operationId",
                                op(),
                                "subjectId",
                                g.entity(),
                                "assertionText",
                                "DataPropertyAssertion(<urn:test:inspectionCode> <urn:test:issue:"+g.graph()+"> \""+value+"\"^^<http://www.w3.org/2001/XMLSchema#decimal>)",
                                "validityKind",
                                validity,
                                "evidenceIds",
                                List.of(g.evidence())));
        if (from != null) r.put("validFrom", from);
        if (to != null) r.put("validTo", to);
        return r;
    }

    private JsonNode propose(
            Graph g, String value, String validity, String from, String to, int status)
            throws Exception {
        return call(
                "POST",
                "/graphs/" + g.graph() + "/statements",
                "member",
                workspace,
                payload(g, value, validity, from, to),
                status);
    }

    private void review(Graph g, JsonNode fact) throws Exception {
        call(
                "POST",
                "/graphs/" + g.graph() + "/statements/" + fact.path("id").asText() + "/review",
                "owner",
                workspace,
                Map.of(
                        "expectedRevision",
                        1,
                        "action",
                        "ACCEPT",
                        "reason",
                        "质量验收",
                        "operationId",
                        op()),
                200);
    }

    private long graphVersion(String graph) {
        return jdbc.queryForObject(
                "SELECT mutation_version FROM mate_semantic_graph WHERE id=?", Long.class, graph);
    }

    private static String id() {
        return com.baomidou.mybatisplus.core.toolkit.IdWorker.getIdStr();
    }

    private static String op() {
        return UUID.randomUUID().toString();
    }

    private record Graph(String kb, String graph, String entity, String raw, String evidence) {}
}
