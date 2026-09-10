package vip.mate.semantic;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import vip.mate.semantic.support.SemanticHttpFixture;
import vip.mate.semantic.query.SemanticContextService;
import vip.mate.semantic.query.SemanticContextDtos.Request;
import vip.mate.semantic.web.SemanticApiException;

class SemanticContextIntegrationTest extends SemanticHttpFixture {
    @Autowired SemanticContextService contexts;
    @Autowired vip.mate.semantic.tool.SemanticContextTool contextTool;
    private record Fixture(String ontology,String revision,String graph,String kb,Long agent,String actor) {}
    private Fixture fixture(String document) throws Exception { return fixture(owlDocument(document)); }
    private Fixture fixture(Map<String,Object> document) throws Exception {
        String ontology=create();draft(ontology);
        call("PUT","/ontologies/"+ontology+"/draft","member",workspace,saveBody(1,document),200);
        String revision=publish(ontology,2,UUID.randomUUID().toString()).path("id").asText();
        String kb=com.baomidou.mybatisplus.core.toolkit.IdWorker.getIdStr();var now=LocalDateTime.now();
        jdbc.update("INSERT INTO mate_wiki_knowledge_base(id,name,description,status,page_count,raw_count,workspace_id,create_time,update_time,deleted) VALUES(?,?,?,?,?,?,?,?,?,0)",
            Long.valueOf(kb),"Context KB","","active",0,0,Long.valueOf(workspace),now,now);
        String graph=call("PUT","/knowledge-bases/"+kb+"/binding","owner",workspace,Map.of("action","ENABLE","revisionId",revision),200).path("graphId").asText();
        String actor=jdbc.queryForObject("SELECT user_id FROM mate_workspace_member WHERE workspace_id=? AND role='member' AND deleted=0",String.class,Long.valueOf(workspace));
        return new Fixture(ontology,revision,graph,kb,agentWithKnowledgeBase(kb),actor);
    }
    @org.junit.jupiter.params.ParameterizedTest(name="context cardinality: {0}")
    @org.junit.jupiter.params.provider.MethodSource("cardinalityContextRows")
    void cardinalityBoundsAndOptionalFillersSurvivePublishedContext(String label,String expression) throws Exception {
        publishedMatrixConstructIsFullyRetrievable(label,"SubClassOf(:A "+expression+")");
    }
    private static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> cardinalityContextRows() {
        List<org.junit.jupiter.params.provider.Arguments> rows=new ArrayList<>();
        for(String family:List.of("Object","Data"))for(String kind:List.of("Min","Max","Exact"))
            for(int bound=0;bound<=2;bound++)for(boolean qualified:List.of(false,true)) {
                String property=family.equals("Object")?":p":":d";
                String filler=qualified?(family.equals("Object")?" :B":" xsd:integer"):"";
                String expression=family+kind+"Cardinality("+bound+" "+property+filler+")";
                rows.add(org.junit.jupiter.params.provider.Arguments.of(expression,expression));
            }
        assertEquals(36,rows.size());return rows.stream();
    }

    @Test void prefixAliasesPreserveUnicodeIrisAndEscapedLanguageLabels() throws Exception {
        String body="Ontology(<urn:test:prefix-context> Declaration(Class(%s设备)) "
            +"AnnotationAssertion(<http://www.w3.org/2000/01/rdf-schema#label> %s设备 \"测针\\\"A\\\"\"@zh))";
        var first=fixture("Prefix(a:=<urn:test:>) "+body.formatted("a:","a:"));
        var second=fixture("Prefix(other:=<urn:test:>) "+body.formatted("other:","other:"));
        var request=new Request("",Set.of(),null,6000,null);
        var a=contexts.context(workspace,first.actor(),first.agent(),first.graph(),request);
        var b=contexts.context(workspace,second.actor(),second.agent(),second.graph(),request);
        assertEquals(a.axioms().stream().map(v->v.functionalSyntax()).collect(java.util.stream.Collectors.toSet()),
            b.axioms().stream().map(v->v.functionalSyntax()).collect(java.util.stream.Collectors.toSet()));
        assertEquals("urn:test:prefix-context",a.ontology().ontologyIri());
        assertTrue(a.terms().stream().anyMatch(t->t.iri().equals("urn:test:设备")&&t.labels().contains("测针\"A\"")));
        assertTrue(a.axioms().stream().anyMatch(v->v.functionalSyntax().contains("@zh")));
        assertTrue(a.axioms().stream().anyMatch(v->v.kind().equals("Declaration")&&v.signature().contains("urn:test:设备")));
    }

    @Test void standardOntologyIdentityRemainsPinnedWhenNewVersionIsPublished() throws Exception {
        var f=fixture("Ontology(<urn:test:identity> <urn:test:identity:v1> Declaration(Class(<urn:test:Equipment>)))");
        var request=new Request("",Set.of(),null,6000,null);
        var first=contexts.context(workspace,f.actor(),f.agent(),f.graph(),request);
        assertEquals("urn:test:identity",first.ontology().ontologyIri());
        assertEquals("urn:test:identity:v1",first.ontology().versionIri());
        var draft=call("POST","/ontologies/"+f.ontology()+"/draft","member",workspace,Map.of("baseRevisionId",f.revision()),200);
        var saved=call("PUT","/ontologies/"+f.ontology()+"/draft","member",workspace,
            saveBody(draft.path("draftVersion").asLong(),owlDocument("Ontology(<urn:test:identity> <urn:test:identity:v2> Declaration(Class(<urn:test:NewType>)))")),200);
        publish(f.ontology(),saved.path("draftVersion").asLong(),UUID.randomUUID().toString());
        var next=contexts.context(workspace,f.actor(),f.agent(),f.graph(),request);
        assertEquals(first.ontology(),next.ontology());
        var unversioned=fixture("Ontology(<urn:test:no-version> Declaration(Class(<urn:test:Equipment>)))");
        var plain=contexts.context(workspace,unversioned.actor(),unversioned.agent(),unversioned.graph(),request);
        assertEquals("urn:test:no-version",plain.ontology().ontologyIri());
        assertNull(plain.ontology().versionIri(),"Internal revision ID must not be fabricated as an OWL version IRI");
    }

    @org.junit.jupiter.params.ParameterizedTest(name="published context matrix: {0}")
    @org.junit.jupiter.params.provider.MethodSource("contextMatrixRows")
    void publishedMatrixConstructIsFullyRetrievable(String rowId,String fragment) throws Exception {
        boolean metadata=rowId.equals("OWL-072")||rowId.equals("OWL-074");
        String declarations="Declaration(Class(:A)) Declaration(Class(:B)) Declaration(Class(:C)) Declaration(Datatype(:D)) "
            +"Declaration(ObjectProperty(:p)) Declaration(ObjectProperty(:q)) Declaration(ObjectProperty(:r)) "
            +"Declaration(DataProperty(:d)) Declaration(DataProperty(:e)) Declaration(AnnotationProperty(:note)) "
            +"Declaration(NamedIndividual(:a)) Declaration(NamedIndividual(:b)) Declaration(NamedIndividual(:c)) ";
        String document="Prefix(:=<urn:test:>) Prefix(xsd:=<http://www.w3.org/2001/XMLSchema#>) Prefix(rdfs:=<http://www.w3.org/2000/01/rdf-schema#>) "
            +"Ontology(<urn:test:matrix-context> "+(metadata?fragment+" ":"")+declarations+(metadata?"":fragment)+")";
        var f=fixture(document);
        var expected=new vip.mate.semantic.owl.OwlDocumentAdapter().parse("expected","expected",document,
            vip.mate.semantic.core.ontology.OntologyDocumentSyntax.FUNCTIONAL,List.of());
        Set<String> returned=new HashSet<>();
        List<vip.mate.semantic.core.ontology.OntologyAnnotationDescriptor> metadataReturned=new ArrayList<>();
        String cursor=null;int pages=0;
        do {
            var page=contexts.context(workspace,f.actor(),f.agent(),f.graph(),new Request("",Set.of(),null,1800,cursor));
            assertTrue(++pages<100,rowId);
            assertEquals(f.revision(),page.ontology().revisionId());
            assertTrue(page.facts().isEmpty(),rowId+" ontology ABox cannot become accepted business facts");
            assertTrue((json.writeValueAsBytes(page).length+2)/3<=1800,rowId);
            for(var axiom:page.axioms()) {
                assertEquals(f.revision(),axiom.originRevisionId());
                assertTrue(returned.add(axiom.functionalSyntax()),rowId+" duplicated axiom");
            }
            page.ontologyAnnotations().forEach(a->metadataReturned.add(a.annotation()));
            cursor=page.coverage().nextCursor();
        } while(cursor!=null);
        assertEquals(expected.axioms().stream().map(a->a.rendering()).collect(java.util.stream.Collectors.toSet()),returned,rowId);
        assertEquals(expected.ontologyAnnotations(),metadataReturned,rowId);
    }
    private static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> contextMatrixRows() throws Exception {
        var matrix=new com.fasterxml.jackson.databind.ObjectMapper().readTree(java.nio.file.Files.readString(
            java.nio.file.Path.of("..","docs","validation","semantic-owl-01","capability-matrix.json")));
        List<org.junit.jupiter.params.provider.Arguments> rows=new ArrayList<>();
        for(var row:matrix.path("rows")) {
            int id=Integer.parseInt(row.path("id").asText().substring(4));
            if(id>=5&&id<=78)rows.add(org.junit.jupiter.params.provider.Arguments.of(row.path("id").asText(),row.path("positive_spec").asText()));
        }
        assertEquals(74,rows.size());return rows.stream();
    }

    @Test void pinnedVersionKeepsOntologyAboxSeparateAndRechecksAgentAuthorization() throws Exception {
        var f=fixture("Ontology(<urn:test:context> Declaration(Class(<urn:test:Equipment>)) Declaration(NamedIndividual(<urn:test:example>)) ClassAssertion(<urn:test:Equipment> <urn:test:example>))");
        var unpublished=call("POST","/ontologies/"+f.ontology()+"/draft","member",workspace,Map.of("baseRevisionId",f.revision()),200);
        call("PUT","/ontologies/"+f.ontology()+"/draft","member",workspace,
            saveBody(unpublished.path("draftVersion").asLong(),owlDocument("Ontology(<urn:test:context> Declaration(Class(<urn:test:DraftOnlySecret>)))")),200);
        var request=new Request("Equipment",Set.of(),null,6000,null);
        var result=contexts.context(workspace,f.actor(),f.agent(),f.graph(),request);
        assertEquals(f.revision(),result.ontology().revisionId());
        assertFalse(json.writeValueAsString(result).contains("DraftOnlySecret"),"Unpublished vocabulary must not leak to ordinary Agent context");
        assertTrue(result.facts().isEmpty());assertEquals("NOT_RUN",result.reasoning().status());
        assertTrue(result.axioms().stream().anyMatch(a->a.kind().equals("ClassAssertion")&&a.origin().equals("ONTOLOGY_ASSERTION")));
        assertTrue(result.axioms().stream().allMatch(a->a.originRevisionId().equals(f.revision())));
        String newerRevision=publish(f.ontology(),unpublished.path("draftVersion").asLong()+1,UUID.randomUUID().toString()).path("id").asText();
        assertNotEquals(f.revision(),newerRevision);
        call("POST","/ontologies/"+f.ontology()+"/draft","member",workspace,Map.of("baseRevisionId",newerRevision),200);
        var afterPublication=contexts.context(workspace,f.actor(),f.agent(),f.graph(),request);
        assertEquals(f.revision(),afterPublication.ontology().revisionId(),"A newer publication must not implicitly rebind the graph");
        assertFalse(json.writeValueAsString(afterPublication).contains("DraftOnlySecret"));
        assertEquals(result.axioms(),afterPublication.axioms());
        when(wikiKnowledgeBases.findVisibleById(f.agent(),Long.valueOf(f.kb()))).thenReturn(null);
        assertEquals(404,assertThrows(SemanticApiException.class,()->contexts.context(workspace,f.actor(),f.agent(),f.graph(),request)).status());
        assertThrows(SemanticApiException.class,()->contexts.context(otherWorkspace,f.actor(),f.agent(),f.graph(),request));
    }
    @Test void importedOntologyAnnotationsKeepTheirArtifactAndNestedProvenance() throws Exception {
        String imported="Ontology(<urn:test:shared-metadata> Annotation(Annotation(<urn:test:note> \"import provenance\") <urn:test:note> \"shared metadata\") Declaration(AnnotationProperty(<urn:test:note>)) Declaration(Class(<urn:test:Shared>)))";
        var lock=vip.mate.semantic.core.ontology.LockedImport.fromText("urn:test:shared-metadata","urn:test:shared-metadata",Optional.empty(),
            vip.mate.semantic.core.ontology.OntologyDocumentSyntax.FUNCTIONAL,imported,"shared-metadata-artifact");
        var input=new LinkedHashMap<>(owlDocument("Ontology(<urn:test:root-metadata> Import(<urn:test:shared-metadata>) Annotation(<urn:test:note> \"root metadata\") Declaration(Class(<urn:test:Root>)))"));
        input.put("imports",List.of(lock));
        var f=fixture(input);
        var before=persistedSemanticState();
        var page=contexts.context(workspace,f.actor(),f.agent(),f.graph(),new Request("",Set.of(),null,6000,null));
        assertFalse(page.coverage().truncated());
        assertEquals(2,page.ontologyAnnotations().size());
        var root=page.ontologyAnnotations().stream().filter(a->a.artifactId()==null).findFirst().orElseThrow();
        var shared=page.ontologyAnnotations().stream().filter(a->"shared-metadata-artifact".equals(a.artifactId())).findFirst().orElseThrow();
        assertEquals("\"root metadata\"^^xsd:string",root.annotation().valueRendering());
        assertEquals("\"shared metadata\"^^xsd:string",shared.annotation().valueRendering());
        assertEquals("\"import provenance\"^^xsd:string",shared.annotation().nestedAnnotations().getFirst().valueRendering());
        assertEquals(f.revision(),shared.originRevisionId());
        assertTrue(page.facts().isEmpty());
        assertEquals(before,persistedSemanticState());
    }

    @Test void nestedOntologyAnnotationsSurviveBudgetedContextWithoutBecomingAxioms() throws Exception {
        StringBuilder document=new StringBuilder("Ontology(<urn:test:metadata> ");
        for(int i=0;i<12;i++) document.append("Annotation(Annotation(<urn:test:note> \"nested\") <urn:test:note> \"metadata-")
            .append(i).append(" ").append("x".repeat(150)).append("\") ");
        document.append("Declaration(AnnotationProperty(<urn:test:note>)) Declaration(Class(<urn:test:Equipment>)))");
        var f=fixture(document.toString());
        var expected=new vip.mate.semantic.owl.OwlDocumentAdapter().parse("expected","expected",document.toString(),
            vip.mate.semantic.core.ontology.OntologyDocumentSyntax.FUNCTIONAL,List.of()).ontologyAnnotations();
        List<vip.mate.semantic.core.ontology.OntologyAnnotationDescriptor> annotations=new ArrayList<>();
        String cursor=null;int pages=0;
        do {
            var page=contexts.context(workspace,f.actor(),f.agent(),f.graph(),new Request("",Set.of(),null,1800,cursor));
            assertTrue(++pages<30);
            assertTrue((json.writeValueAsBytes(page).length+2)/3<=1800);
            assertTrue(page.facts().isEmpty());
            assertTrue(page.axioms().stream().noneMatch(a->a.kind().equals("OntologyAnnotation")));
            for(var item:page.ontologyAnnotations()) {
                assertEquals(f.revision(),item.originRevisionId());assertNull(item.artifactId());
                annotations.add(item.annotation());
            }
            assertEquals(12-annotations.size(),page.coverage().omittedOntologyAnnotations());
            assertEquals(0,page.coverage().omittedConclusions());
            cursor=page.coverage().nextCursor();
        } while(cursor!=null);
        assertTrue(pages>1);
        assertEquals(expected,annotations);
    }

    @Test void complexAxiomsSurviveOrdinaryAgentContextPaginationWithoutFlattening() throws Exception {
        String document = """
            Ontology(<urn:test:complex-context>
              Declaration(Class(<urn:test:Equipment>))
              Declaration(Class(<urn:test:Probe>))
              Declaration(ObjectProperty(<urn:test:hasPart>))
              Declaration(DataProperty(<urn:test:serial>))
              Declaration(NamedIndividual(<urn:test:machine>))
              SubClassOf(Annotation(<http://www.w3.org/2000/01/rdf-schema#comment> "nested constraint")
                <urn:test:Equipment> ObjectIntersectionOf(
                  ObjectSomeValuesFrom(<urn:test:hasPart> <urn:test:Probe>)
                  ObjectAllValuesFrom(<urn:test:hasPart> ObjectUnionOf(<urn:test:Probe> ObjectComplementOf(<urn:test:Equipment>)))
                  ObjectMaxCardinality(2 <urn:test:hasPart> <urn:test:Probe>)))
              HasKey(<urn:test:Equipment> () (<urn:test:serial>))
              ClassAssertion(ObjectSomeValuesFrom(<urn:test:hasPart> <urn:test:Probe>) <urn:test:machine>)
            )
            """;
        var f=fixture(document);
        var manager=org.semanticweb.owlapi.apibinding.OWLManager.createOWLOntologyManager();
        var expected=manager.loadOntologyFromOntologyDocument(
            new org.semanticweb.owlapi.io.StringDocumentSource(document));
        var received=new StringBuilder("Ontology(<urn:test:received> ");
        var ids=new HashSet<String>();
        var page=contexts.context(workspace,f.actor(),f.agent(),f.graph(),new Request("",Set.of(),null,1800,null));
        var snapshot=page.snapshot();
        assertTrue(page.coverage().truncated(),"Exercise real continuation, not just a single response");
        int pages=0;
        while(true) {
            assertTrue(++pages<=expected.getAxiomCount());
            assertEquals(snapshot,page.snapshot());
            assertEquals(f.revision(),page.ontology().revisionId());
            assertTrue(page.facts().isEmpty(),"Ontology ABox must not become accepted business facts");
            for(var axiom:page.axioms()) {
                assertTrue(ids.add(axiom.id()));
                assertEquals("ONTOLOGY_ASSERTION",axiom.origin());
                assertEquals(f.revision(),axiom.originRevisionId());
                received.append(axiom.functionalSyntax()).append(' ');
            }
            String cursor=page.coverage().nextCursor();
            if(cursor==null)break;
            page=contexts.context(workspace,f.actor(),f.agent(),f.graph(),new Request("",Set.of(),null,1800,cursor));
        }
        received.append(')');
        var actual=manager.loadOntologyFromOntologyDocument(
            new org.semanticweb.owlapi.io.StringDocumentSource(received.toString()));
        assertEquals(expected.getAxioms(),actual.getAxioms(),
            "Context must preserve nested expressions, cardinality, keys, annotations and anonymous class assertions structurally");
    }

    private Map<String,List<String>> persistedSemanticState() throws Exception {
        Map<String,List<String>> state=new TreeMap<>();
        try(var connection=Objects.requireNonNull(jdbc.getDataSource()).getConnection();
            var tables=connection.getMetaData().getTables(connection.getCatalog(),null,"%",new String[]{"TABLE"})) {
            while(tables.next()) {
                String table=tables.getString("TABLE_NAME");
                if(!table.toLowerCase(Locale.ROOT).startsWith("mate_semantic_"))continue;
                assertTrue(table.matches("[A-Za-z0-9_]+"));
                List<String> rows=new ArrayList<>();
                for(var row:jdbc.queryForList("SELECT * FROM "+table))rows.add(json.writeValueAsString(new TreeMap<>(row)));
                Collections.sort(rows);state.put(table,rows);
            }
        }
        assertFalse(state.isEmpty(),"Read-only assertion must inspect actual persisted semantic tables");
        return state;
    }

    @Test void ordinaryToolReturnsRealTransitiveInferenceWithoutPromotingItToFact() throws Exception {
        var f=fixture("""
            Ontology(<urn:test:context-inference>
              Declaration(Class(<urn:test:Probe>)) Declaration(Class(<urn:test:Sensor>)) Declaration(Class(<urn:test:Equipment>))
              SubClassOf(<urn:test:Probe> <urn:test:Sensor>) SubClassOf(<urn:test:Sensor> <urn:test:Equipment>))
            """);
        var tool=contextTool;
        var callback=Arrays.stream(org.springframework.ai.support.ToolCallbacks.from(tool))
            .filter(c->c.getToolDefinition().name().equals("semantic_context")).findFirst().orElseThrow();
        String schema=callback.getToolDefinition().inputSchema();
        assertFalse(schema.contains("workspaceId"));assertFalse(schema.contains("actorId"));
        var principal=vip.mate.agent.context.ChatOrigin.web("context-real-inference","unused",Long.valueOf(workspace),
            null,null,Long.valueOf(f.actor())).withAgent(f.agent()).toToolContext();
        var beforeRead=persistedSemanticState();
        var response=json.readTree(callback.call(json.writeValueAsString(Map.of(
            "graphId",f.graph(),"question","","budget",6000,"reasoning",Map.of("task","CLASSIFICATION","scope","TBOX_ONLY"))),principal));
        assertEquals("COMPLETE_FOR_REQUEST",response.path("reasoning").path("status").asText());
        assertEquals("CONSISTENT",response.path("reasoning").path("outcome").asText());
        assertEquals(f.revision(),response.path("ontology").path("revisionId").asText());
        assertTrue(response.path("facts").isEmpty());
        boolean transitive=false;
        for(var conclusion:response.path("reasoning").path("conclusions")) {
            if(conclusion.path("assertion").asText().equals("SubClassOf(<urn:test:Probe> <urn:test:Equipment>)"))transitive=true;
            assertEquals("INFERRED",conclusion.path("origin").asText());
            assertEquals("UNAVAILABLE",conclusion.path("explanationStatus").asText());
        }
        assertTrue(transitive,"Real HermiT result must contain the transitive, non-asserted superclass");
        assertEquals(beforeRead,persistedSemanticState(),"Ordinary context and real inference must not mutate any semantic table");
    }

    @Test void evidenceIsReadBackAndMutuallyExclusiveTimesNeverMix() throws Exception {
        var f=fixture("Ontology(<urn:test:context> Declaration(Class(<urn:test:Equipment>)) Declaration(DataProperty(<urn:test:reading>)))");
        var entity=call("POST","/graphs/"+f.graph()+"/entities","member",workspace,
            Map.of("iri","urn:test:machine","assertedTypes",List.of("urn:test:Equipment"),"displayName","Machine"),200);
        String text="测针😀读数 1，旧读数 2，日期不明 3", raw=com.baomidou.mybatisplus.core.toolkit.IdWorker.getIdStr();var now=LocalDateTime.now();
        jdbc.update("INSERT INTO mate_wiki_raw_material(id,kb_id,title,source_type,original_content,file_size,processing_status,create_time,update_time,deleted) VALUES(?,?,?,?,?,?,?,?,?,0)",
            Long.valueOf(raw),Long.valueOf(f.kb()),"Readings","text",text,text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length,"completed",now,now);
        String snapshot=call("POST","/graphs/"+f.graph()+"/imports","member",workspace,
            Map.of("sourceKind","WIKI_RAW","sourceRef",raw,"operationId",UUID.randomUUID().toString()),200).path("snapshotId").asText();
        String evidence=call("POST","/graphs/"+f.graph()+"/snapshots/"+snapshot+"/evidence","member",workspace,
            Map.of("operationId",UUID.randomUUID().toString(),"startCodePoint",0,"endCodePoint",text.codePointCount(0,text.length()),"exactQuote",text),200).path("id").asText();
        String secondRaw=com.baomidou.mybatisplus.core.toolkit.IdWorker.getIdStr();
        jdbc.update("INSERT INTO mate_wiki_raw_material(id,kb_id,title,source_type,original_content,file_size,processing_status,create_time,update_time,deleted) VALUES(?,?,?,?,?,?,?,?,?,0)",
            Long.valueOf(secondRaw),Long.valueOf(f.kb()),"Independent reading","text",text,text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length,"completed",now,now);
        String secondSnapshot=call("POST","/graphs/"+f.graph()+"/imports","member",workspace,
            Map.of("sourceKind","WIKI_RAW","sourceRef",secondRaw,"operationId",UUID.randomUUID().toString()),200).path("snapshotId").asText();
        String secondEvidence=call("POST","/graphs/"+f.graph()+"/snapshots/"+secondSnapshot+"/evidence","member",workspace,
            Map.of("operationId",UUID.randomUUID().toString(),"startCodePoint",0,"endCodePoint",text.codePointCount(0,text.length()),"exactQuote",text),200).path("id").asText();
        for(int i=1;i<=3;i++) {
            Map<String,Object> fact=new HashMap<>(Map.of("operationId",UUID.randomUUID().toString(),"subjectId",entity.path("id").asText(),
                "assertionText","DataPropertyAssertion(<urn:test:reading> <urn:test:machine> \""+i+"\"^^<http://www.w3.org/2001/XMLSchema#integer>)",
                "validityKind",i==3?"UNKNOWN":"INTERVAL","evidenceIds",i==1?List.of(evidence,secondEvidence):List.of(evidence)));
            if(i==1)fact.put("validFrom","2026-01-01T00:00:00Z");
            if(i==2)fact.put("validTo","2026-01-01T00:00:00Z");
            String id=call("POST","/graphs/"+f.graph()+"/statements","member",workspace,fact,200).path("id").asText();
            call("POST","/graphs/"+f.graph()+"/statements/"+id+"/review","owner",workspace,
                Map.of("expectedRevision",1,"action","ACCEPT","reason","Verified source","operationId",UUID.randomUUID().toString()),200);
        }
        var request=new Request("Machine",Set.of("urn:test:machine"),java.time.Instant.parse("2026-01-01T00:00:00Z"),6000,null);
        var result=contexts.context(workspace,f.actor(),f.agent(),f.graph(),request);
        assertEquals(1,result.facts().size());assertTrue(result.facts().getFirst().assertion().contains("\"1\""));
        assertTrue(result.evidence().stream().anyMatch(e->e.id().equals(evidence)&&e.snapshotId().equals(snapshot)));
        assertTrue(result.evidence().stream().allMatch(e->e.exactQuote().equals(text)));
        for(var returned:result.evidence()) {
            assertEquals(0,returned.startCodePoint());
            assertEquals(text.codePointCount(0,text.length()),returned.endCodePoint());
            assertTrue(returned.endCodePoint()<text.length(),"Supplementary Unicode must not use UTF-16 offsets");
        }
        jdbc.update("UPDATE mate_wiki_raw_material SET original_content=? WHERE id=?",text+"，后续校准记录要求复核",Long.valueOf(raw));
        call("POST","/graphs/"+f.graph()+"/source-changes/scan","member",workspace,
            Map.of("sourceKind","WIKI_RAW","sourceRef",raw,"operationId",UUID.randomUUID().toString()),200);
        var reviewedContext=contexts.context(workspace,f.actor(),f.agent(),f.graph(),request);
        assertEquals("PENDING",reviewedContext.facts().getFirst().sourceReviews().getFirst().reviewState());
        assertEquals(text,reviewedContext.evidence().getFirst().exactQuote(),"Original evidence remains immutable");
        var pendingReview=reviewedContext.facts().getFirst().sourceReviews().getFirst();
        call("POST","/graphs/"+f.graph()+"/source-changes/items/"+pendingReview.reviewId()+"/decision","owner",workspace,
            Map.of("operationId",UUID.randomUUID().toString(),"expectedObservedDigest",pendingReview.observedDigest(),
                "expectedGraphVersion",reviewedContext.graphMutationVersion(),"decision","KEEP_HISTORICAL","reason","Preserve observed reading"),200);
        var historical=contexts.context(workspace,f.actor(),f.agent(),f.graph(),request);
        assertEquals("KEEP_HISTORICAL",historical.facts().getFirst().sourceReviews().getFirst().decision());

        call("POST","/graphs/"+f.graph()+"/sources/withdraw","owner",workspace,
            Map.of("sourceKind","WIKI_RAW","sourceRef",raw,"reason","Superseded","operationId",UUID.randomUUID().toString()),200);
        var surviving=contexts.context(workspace,f.actor(),f.agent(),f.graph(),request);
        assertEquals(1,surviving.facts().size());
        assertEquals(List.of(secondEvidence),surviving.facts().getFirst().evidenceIds());
        assertEquals(secondSnapshot,surviving.evidence().getFirst().snapshotId());
        call("POST","/graphs/"+f.graph()+"/sources/withdraw","owner",workspace,
            Map.of("sourceKind","WIKI_RAW","sourceRef",secondRaw,"reason","Superseded","operationId",UUID.randomUUID().toString()),200);
        assertTrue(contexts.context(workspace,f.actor(),f.agent(),f.graph(),request).facts().isEmpty());
        jdbc.update("UPDATE mate_semantic_graph SET enabled=FALSE WHERE id=?",f.graph());
        assertEquals("GRAPH_DISABLED",assertThrows(SemanticApiException.class,()->contexts.context(workspace,f.actor(),f.agent(),f.graph(),request)).code());
    }

    @Test void signedPaginationCannotMixGraphVersionsOrQuestions() throws Exception {
        StringBuilder text=new StringBuilder("Ontology(<urn:test:large>");
        for(int i=0;i<120;i++)text.append(" Declaration(Class(<urn:test:C").append(i).append(">))");
        text.append(')');var f=fixture(text.toString());
        var first=contexts.context(workspace,f.actor(),f.agent(),f.graph(),new Request("",Set.of(),null,1800,null));
        assertTrue((json.writeValueAsBytes(first).length+2)/3<=1800,"Serialized context must fit its stated estimated budget");
        assertTrue(first.coverage().truncated());assertFalse(first.coverage().dependencyClosureComplete());
        String cursor=first.coverage().nextCursor();assertNotNull(cursor);
        var next=contexts.context(workspace,f.actor(),f.agent(),f.graph(),new Request("",Set.of(),null,1800,cursor));
        assertEquals(first.snapshot(),next.snapshot());
        var ids=first.axioms().stream().map(a->a.id()).toList();assertTrue(next.axioms().stream().noneMatch(a->ids.contains(a.id())));
        var allIds=new java.util.HashSet<String>();
        var page=first;
        int pages=0;
        while(true) {
            assertTrue(++pages<=120,"Pagination must terminate");
            assertEquals(first.snapshot(),page.snapshot());
            assertTrue((json.writeValueAsBytes(page).length+2)/3<=1800);
            for(var axiom:page.axioms())assertTrue(allIds.add(axiom.id()),"Repeated axiom across pages");
            String continuation=page.coverage().nextCursor();
            if(continuation==null)break;
            page=contexts.context(workspace,f.actor(),f.agent(),f.graph(),new Request("",Set.of(),null,1800,continuation));
        }
        assertEquals(120,allIds.size(),"Every published declaration must be retrievable");

        assertEquals(400,assertThrows(SemanticApiException.class,()->contexts.context(workspace,f.actor(),f.agent(),f.graph(),new Request("different",Set.of(),null,1800,cursor))).status());
        assertEquals(400,assertThrows(SemanticApiException.class,()->contexts.context(workspace,f.actor(),f.agent(),f.graph(),new Request("",Set.of(),null,1800,cursor+"x"))).status());
        jdbc.update("UPDATE mate_semantic_graph SET mutation_version=mutation_version+1 WHERE id=?",f.graph());
        assertEquals("CONTEXT_STALE",assertThrows(SemanticApiException.class,()->contexts.context(workspace,f.actor(),f.agent(),f.graph(),new Request("",Set.of(),null,1800,cursor))).code());
        when(wikiKnowledgeBases.findVisibleById(f.agent(),Long.valueOf(f.kb()))).thenReturn(null);
        assertEquals(404,assertThrows(SemanticApiException.class,()->contexts.context(workspace,f.actor(),f.agent(),f.graph(),
            new Request("",Set.of(),null,1800,cursor))).status(),"A signed cursor cannot bypass revoked KB visibility");

    }
}
