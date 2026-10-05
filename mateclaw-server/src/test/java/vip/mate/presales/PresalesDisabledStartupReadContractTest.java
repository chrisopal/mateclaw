package vip.mate.presales;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import vip.mate.semantic.support.SemanticHttpFixture;

@Import({
    PresalesAccess.class,
    PresalesService.class,
    vip.mate.presales.repository.PresalesProjectRepository.class,
    vip.mate.presales.repository.PresalesArtifactRepository.class,
    PresalesSourceAuthorization.class,
    vip.mate.wiki.service.WikiSourceReadService.class,
    vip.mate.wiki.repository.WikiSourceReadRepository.class,
    vip.mate.semantic.source.SourceGovernanceReadService.class,
    vip.mate.semantic.source.repository.SourceGovernanceReadRepository.class,
    PresalesController.class,
    PresalesSourceQueryService.class,
    PresalesProjectQueryService.class,
    PresalesExceptionHandler.class,
    PresalesArtifactRenderer.class,
    vip.mate.workspace.core.service.ProjectSourceAccess.class,
    vip.mate.workspace.core.service.ProjectAuthorityFence.class
})
@TestPropertySource(
        properties = {
            "mateclaw.presales.enabled=true",
            "mateclaw.semantic.enabled=false",
            "spring.datasource.url=jdbc:h2:mem:presales_disabled_startup;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
        })
class PresalesDisabledStartupReadContractTest extends SemanticHttpFixture {
    private static final String RELEASE = "9007199254740993301";
    private static final String SOLUTION = "9007199254740993302";
    private static final byte[] BYTES =
            "Frozen published artifact\n".getBytes(StandardCharsets.UTF_8);
    @Autowired ApplicationContext context;

    private record Frozen(
            ObjectNode project,
            String kb,
            String raw,
            String graph,
            String snapshot,
            String evidence) {}

    private MockHttpServletResponse presales(
            String method, String path, String role, String scope, Object body) throws Exception {
        var request =
                MockMvcRequestBuilders.request(
                                org.springframework.http.HttpMethod.valueOf(method),
                                "/api/v1/presales" + path)
                        .contentType("application/json");
        if (role != null) request.header("Authorization", tokens.get(role));
        if (scope != null) request.header("X-Workspace-Id", scope);
        if (body != null) request.content(json.writeValueAsString(body));
        return mvc.perform(request).andReturn().getResponse();
    }

    // Synthetic persisted history, not a publication or real legacy-upgrade fixture.
    private Frozen frozen() throws Exception {
        var created =
                presales(
                        "POST",
                        "/projects",
                        "member",
                        workspace,
                        Map.of(
                                "name",
                                "Disabled startup history",
                                "customer",
                                "Synthetic customer",
                                "expectedVersion",
                                0,
                                "operationId",
                                UUID.randomUUID().toString()));
        assertEquals(200, created.getStatus(), created.getContentAsString());
        var p = (ObjectNode) json.readTree(created.getContentAsString()).path("data");
        String kb = id(),
                raw = id(),
                ontology = id(),
                revision = id(),
                graph = id(),
                snapshot = id(),
                evidence = id();
        var now = LocalDateTime.now();
        jdbc.update(
                "INSERT INTO mate_wiki_knowledge_base(id,name,workspace_id,create_time,update_time,deleted) VALUES(?,?,?,?,?,0)",
                Long.valueOf(kb),
                "Frozen sources",
                Long.valueOf(workspace),
                now,
                now);
        jdbc.update(
                "INSERT INTO mate_wiki_raw_material(id,kb_id,title,original_content,extracted_text,create_time,update_time,deleted) VALUES(?,?,'Synthetic fit',?,?,?,?,0)",
                Long.valueOf(raw),
                Long.valueOf(kb),
                "fit source",
                "fit source",
                now,
                now);
        var kbRow = new vip.mate.wiki.model.WikiKnowledgeBaseEntity();
        kbRow.setId(Long.valueOf(kb));
        kbRow.setWorkspaceId(Long.valueOf(workspace));
        kbRow.setDeleted(0);
        when(wikiKnowledgeBases.getById(Long.valueOf(kb))).thenReturn(kbRow);
        jdbc.update(
                "INSERT INTO mate_semantic_ontology(id,workspace_id,name,description,updated_at) VALUES(?,?,?,?,?)",
                ontology,
                Long.valueOf(workspace),
                "Historical ontology",
                "Synthetic",
                now);
        jdbc.update(
                "INSERT INTO mate_semantic_ontology_revision(id,ontology_id,version,draft_version,revision_state,name,description,definition_json,available_for_new_bindings) VALUES(?,?,1,1,'PUBLISHED','Historical','Synthetic','{}',FALSE)",
                revision,
                ontology);
        jdbc.update(
                "INSERT INTO mate_semantic_graph(id,workspace_id,kb_id,ontology_revision_id,enabled,created_at,updated_at) VALUES(?,?,?,?,FALSE,?,?)",
                graph,
                Long.valueOf(workspace),
                Long.valueOf(kb),
                revision,
                now,
                now);
        jdbc.update(
                "INSERT INTO mate_semantic_source_snapshot(id,graph_id,source_kind,source_id,source_title,capture_version,text_digest,text_content,created_by,created_at) VALUES(?,?,'WIKI_RAW',?,'Synthetic fit source',1,?,'fit source',?,?)",
                snapshot,
                graph,
                raw,
                PresalesArtifactRenderer.digest("fit source".getBytes(StandardCharsets.UTF_8)),
                p.path("ownerId").asText(),
                now);
        jdbc.update(
                "INSERT INTO mate_semantic_evidence(id,graph_id,snapshot_id,operation_id,start_codepoint,end_codepoint,exact_quote,created_by,created_at) VALUES(?,?,?,?,0,10,'fit source',?,?)",
                evidence,
                graph,
                snapshot,
                UUID.randomUUID().toString(),
                p.path("ownerId").asText(),
                now);
        p.withArray("materials").addObject().put("kbId", kb).put("graphId", graph);
        var baseline = p.withArray("baselines").addObject().put("id", "baseline");
        baseline.putArray("references");
        var solution =
                p.withArray("solutions")
                        .addObject()
                        .put("id", SOLUTION)
                        .put("baselineId", "baseline")
                        .put("provisional", false);
        solution.putArray("sections");
        solution.putArray("fitGapRefs").add("fit");
        var fit =
                p.withArray("fitGaps")
                        .addObject()
                        .put("id", "fit")
                        .put("status", "FIT")
                        .put("graphId", graph);
        fit.putArray("evidenceIds").add(evidence);
        var release =
                p.withArray("releases")
                        .addObject()
                        .put("id", RELEASE)
                        .put("solutionId", SOLUTION)
                        .put("baselineId", "baseline")
                        .put("status", "PUBLISHED");
        release.putArray("files")
                .addObject()
                .put("filename", "solution.md")
                .put("sha256", PresalesArtifactRenderer.digest(BYTES));
        var frozenRelease = release.deepCopy();
        var frozen = release.putObject("handoffSnapshot");
        frozen.put("schemaVersion", 1)
                .put("workspaceId", workspace)
                .put("engagementId", p.path("id").asText())
                .put("caseRef", p.path("id").asText());
        frozen.set("release", frozenRelease);
        frozen.set("baseline", baseline.deepCopy());
        frozen.set("solution", solution.deepCopy());
        frozen.set("materials", p.path("materials").deepCopy());
        frozen.set("fitGaps", p.path("fitGaps").deepCopy());
        frozen.putArray("sourceRefs");
        persist(p);
        jdbc.update(
                "INSERT INTO mate_presales_artifact(project_id,release_id,filename,digest,content_base64) VALUES(?,?,'solution.md',?,?)",
                p.path("id").asText(),
                RELEASE,
                PresalesArtifactRenderer.digest(BYTES),
                Base64.getEncoder().encodeToString(BYTES));
        return new Frozen(p, kb, raw, graph, snapshot, evidence);
    }

    private static String id() {
        return com.baomidou.mybatisplus.core.toolkit.IdWorker.getIdStr();
    }

    private void persist(ObjectNode p) throws Exception {
        jdbc.update(
                "UPDATE mate_presales_project SET body_json=? WHERE id=?",
                json.writeValueAsString(p),
                p.path("id").asText());
    }

    private String path(Frozen f, String suffix) {
        return "/projects/" + f.project().path("id").asText() + suffix;
    }

    private String facts(Frozen f) throws Exception {
        String project = f.project().path("id").asText();
        return json.writeValueAsString(
                List.of(
                        jdbc.queryForMap(
                                "SELECT body_json,version FROM mate_presales_project WHERE id=?",
                                project),
                        jdbc.queryForList(
                                "SELECT release_id,filename,digest,content_base64 FROM mate_presales_artifact WHERE project_id=? ORDER BY release_id,filename",
                                project),
                        jdbc.queryForList(
                                "SELECT * FROM mate_presales_revision WHERE project_id=?", project),
                        jdbc.queryForList(
                                "SELECT * FROM mate_presales_operation WHERE workspace_id=? ORDER BY actor_id,operation_id",
                                workspace)));
    }

    private void reads(Frozen f, int status) throws Exception {
        for (String suffix :
                List.of(
                        "/releases/" + RELEASE + "/files/solution.md",
                        "/releases/" + RELEASE + "/preview/solution.md",
                        "/releases/" + RELEASE + "/handoff",
                        "/handoff")) {
            var response =
                    presales(
                            "GET",
                            path(f, suffix),
                            suffix.contains("preview") ? "owner" : "viewer",
                            workspace,
                            null);
            assertEquals(
                    status, response.getStatus(), suffix + ": " + response.getContentAsString());
            if (status == 200 && (suffix.contains("files") || suffix.contains("preview")))
                assertArrayEquals(BYTES, response.getContentAsByteArray());
        }
    }

    @Test
    void startupActuallyOmitsSemanticMutationBeans() throws Exception {
        assertFalse(context.getBean(vip.mate.semantic.config.SemanticProperties.class).isEnabled());
        assertTrue(
                context.getBeansOfType(vip.mate.semantic.query.SemanticQueryService.class)
                        .isEmpty());
        assertTrue(
                context.getBeansOfType(vip.mate.semantic.graph.GraphApplicationService.class)
                        .isEmpty());
        assertTrue(
                context.getBeansOfType(
                                vip.mate.semantic.statement.StatementApplicationService.class)
                        .isEmpty());
        assertNotNull(context.getBean(vip.mate.semantic.source.SourceGovernanceReadService.class));
        assertFalse(call("GET", "/status", "viewer", null, null, 200).path("enabled").asBoolean());
        call("GET", "/ontologies", "owner", workspace, null, 404);
    }

    @Test
    void validFrozenHistoryReadsExactBytesAndHandoffWithoutSemanticBeans() throws Exception {
        var f = frozen();
        String before = facts(f);
        reads(f, 200);
        var exact =
                presales(
                        "GET",
                        path(f, "/releases/" + RELEASE + "/handoff"),
                        "viewer",
                        workspace,
                        null);
        var latest = presales("GET", path(f, "/handoff"), "viewer", workspace, null);
        var expected =
                (ObjectNode) f.project().path("releases").get(0).path("handoffSnapshot").deepCopy();
        expected.put("releaseId", RELEASE);
        assertEquals(expected, json.readTree(exact.getContentAsString()).path("data"));
        assertEquals(
                json.readTree(exact.getContentAsString()),
                json.readTree(latest.getContentAsString()));
        assertEquals(before, facts(f));
    }

    @Test
    void frozenFitSourceRevocationStillStopsAllFourReadsAtDisabledStartup() throws Exception {
        var f = frozen();
        String before = facts(f);
        for (String change : List.of("withdrawn", "deleted", "excluded", "unsupported-kind")) {
            try {
                switch (change) {
                    case "withdrawn" ->
                            jdbc.update(
                                    "INSERT INTO mate_semantic_source_governance(graph_id,source_kind,source_id,state,actor_id,reason,created_at) VALUES(?,'WIKI_RAW',?,'WITHDRAWN',?,'synthetic revocation',?)",
                                    f.graph(),
                                    f.raw(),
                                    f.project().path("ownerId").asText(),
                                    LocalDateTime.now());
                    case "deleted" ->
                            jdbc.update(
                                    "UPDATE mate_wiki_raw_material SET deleted=1 WHERE id=?",
                                    Long.valueOf(f.raw()));
                    case "excluded" ->
                            jdbc.update(
                                    "INSERT INTO mate_semantic_snapshot_exclusion(graph_id,snapshot_id,actor_id,reason,created_at) VALUES(?,?,?,'synthetic exclusion',?)",
                                    f.graph(),
                                    f.snapshot(),
                                    f.project().path("ownerId").asText(),
                                    LocalDateTime.now());
                    case "unsupported-kind" ->
                            jdbc.update(
                                    "UPDATE mate_semantic_source_snapshot SET source_kind='wiki_raw' WHERE id=?",
                                    f.snapshot());
                }
                reads(f, 404);
                assertEquals(before, facts(f));
            } finally {
                jdbc.update(
                        "DELETE FROM mate_semantic_source_governance WHERE graph_id=? AND source_id=?",
                        f.graph(),
                        f.raw());
                jdbc.update(
                        "DELETE FROM mate_semantic_snapshot_exclusion WHERE graph_id=? AND snapshot_id=?",
                        f.graph(),
                        f.snapshot());
                jdbc.update(
                        "UPDATE mate_wiki_raw_material SET deleted=0 WHERE id=?",
                        Long.valueOf(f.raw()));
                jdbc.update(
                        "UPDATE mate_semantic_source_snapshot SET source_kind='WIKI_RAW' WHERE id=?",
                        f.snapshot());
            }
            reads(f, 200);
        }
    }

    @Test
    void employeeIntersectionRemainsCurrentWhenSemanticBeansAreAbsent() throws Exception {
        var f = frozen();
        String employee = id();
        var now = LocalDateTime.now();
        jdbc.update(
                "INSERT INTO mate_agent(id,name,workspace_id,enabled,deleted,wiki_disabled,create_time,update_time) VALUES(?,?,?,TRUE,0,FALSE,?,?)",
                Long.valueOf(employee),
                "Frozen employee",
                Long.valueOf(workspace),
                now,
                now);
        jdbc.update(
                "INSERT INTO mate_agent_wiki_kb(id,agent_id,kb_id,enabled,deleted) VALUES(?,?,?,TRUE,0)",
                Long.valueOf(id()),
                Long.valueOf(employee),
                Long.valueOf(f.kb()));
        f.project().put("agentId", employee);
        persist(f.project());
        String before = facts(f);
        reads(f, 200);
        for (String change : List.of("binding", "employee", "wiki")) {
            try {
                if (change.equals("binding"))
                    jdbc.update(
                            "UPDATE mate_agent_wiki_kb SET enabled=FALSE WHERE agent_id=?",
                            Long.valueOf(employee));
                if (change.equals("employee"))
                    jdbc.update(
                            "UPDATE mate_agent SET enabled=FALSE WHERE id=?",
                            Long.valueOf(employee));
                if (change.equals("wiki"))
                    jdbc.update(
                            "UPDATE mate_agent SET wiki_disabled=TRUE WHERE id=?",
                            Long.valueOf(employee));
                reads(f, 403);
                assertEquals(before, facts(f));
            } finally {
                jdbc.update(
                        "UPDATE mate_agent SET enabled=TRUE,wiki_disabled=FALSE WHERE id=?",
                        Long.valueOf(employee));
                jdbc.update(
                        "UPDATE mate_agent_wiki_kb SET enabled=TRUE WHERE agent_id=?",
                        Long.valueOf(employee));
            }
        }
    }

    @Test
    void workspaceAndRoleBoundariesDoNotDependOnSemanticEnablement() throws Exception {
        var f = frozen();
        String before = facts(f);
        String file = path(f, "/releases/" + RELEASE + "/files/solution.md");
        assertEquals(401, presales("GET", file, null, workspace, null).getStatus());
        assertEquals(404, presales("GET", file, "owner", otherWorkspace, null).getStatus());
        assertEquals(403, presales("GET", file, "viewer", otherWorkspace, null).getStatus());
        for (String role : List.of("viewer", "member"))
            assertEquals(
                    403,
                    presales(
                                    "GET",
                                    path(f, "/releases/" + RELEASE + "/preview/solution.md"),
                                    role,
                                    workspace,
                                    null)
                            .getStatus());
        assertEquals(before, facts(f));
    }

    @Test
    void newPublicationStillRequiresSemanticAndDoesNotWriteAReceipt() throws Exception {
        var f = frozen();
        String before = facts(f);
        var r =
                presales(
                        "POST",
                        path(f, "/commands"),
                        "owner",
                        workspace,
                        Map.of(
                                "action",
                                "CREATE_RELEASE",
                                "expectedVersion",
                                f.project().path("version"),
                                "operationId",
                                UUID.randomUUID().toString(),
                                "payload",
                                Map.of("solutionId", SOLUTION)));
        assertEquals(409, r.getStatus(), r.getContentAsString());
        assertTrue(r.getContentAsString().contains("SEMANTIC_DISABLED"));
        assertEquals(before, facts(f));
    }

    @Test
    void disabledStartupDoesNotDisablePublishedDigestVerification() throws Exception {
        var f = frozen();
        byte[] changed = "Changed bytes".getBytes(StandardCharsets.UTF_8);
        jdbc.update(
                "UPDATE mate_presales_artifact SET digest=?,content_base64=? WHERE project_id=?",
                PresalesArtifactRenderer.digest(changed),
                Base64.getEncoder().encodeToString(changed),
                f.project().path("id").asText());
        String before = facts(f);
        reads(f, 409);
        assertEquals(before, facts(f));
    }

    @Test
    void incompleteFrozenHistoryStillUsesTheLegacyPublicationGate() throws Exception {
        var f = frozen();
        var release = (ObjectNode) f.project().path("releases").get(0);
        release.remove("handoffSnapshot");
        persist(f.project());
        String before = facts(f);
        for (String route : List.of("files", "preview")) {
            var r =
                    presales(
                            "GET",
                            path(f, "/releases/" + RELEASE + "/" + route + "/solution.md"),
                            "owner",
                            workspace,
                            null);
            assertEquals(409, r.getStatus(), r.getContentAsString());
            assertTrue(r.getContentAsString().contains("SEMANTIC_DISABLED"));
        }
        assertEquals(before, facts(f));
    }
}
