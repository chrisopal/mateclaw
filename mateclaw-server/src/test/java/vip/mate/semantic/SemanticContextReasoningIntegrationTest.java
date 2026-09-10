package vip.mate.semantic;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import vip.mate.semantic.core.reasoning.*;
import vip.mate.semantic.core.reasoning.ReasoningResult.ReasoningStatus;
import vip.mate.semantic.query.SemanticContextService;
import vip.mate.semantic.query.SemanticContextDtos.*;
import vip.mate.semantic.support.SemanticHttpFixture;
import vip.mate.semantic.web.SemanticApiException;

class SemanticContextReasoningIntegrationTest extends SemanticHttpFixture {
    @Autowired SemanticContextService contexts;
    @MockitoBean ReasoningPort worker;
    private record Fixture(String ontology,String revision,String graph,String kb,Long agent,String actor) {}
    private Fixture fixture(String document) throws Exception {
        String ontology=create();draft(ontology);
        call("PUT","/ontologies/"+ontology+"/draft","member",workspace,saveBody(1,owlDocument(document)),200);
        String revision=publish(ontology,2,UUID.randomUUID().toString()).path("id").asText();
        String kb=com.baomidou.mybatisplus.core.toolkit.IdWorker.getIdStr();var now=LocalDateTime.now();
        jdbc.update("INSERT INTO mate_wiki_knowledge_base(id,name,description,status,page_count,raw_count,workspace_id,create_time,update_time,deleted) VALUES(?,?,?,?,?,?,?,?,?,0)",
            Long.valueOf(kb),"Context KB","","active",0,0,Long.valueOf(workspace),now,now);
        String graph=call("PUT","/knowledge-bases/"+kb+"/binding","owner",workspace,Map.of("action","ENABLE","revisionId",revision),200).path("graphId").asText();
        String actor=jdbc.queryForObject("SELECT user_id FROM mate_workspace_member WHERE workspace_id=? AND role='member' AND deleted=0",String.class,Long.valueOf(workspace));
        return new Fixture(ontology,revision,graph,kb,agentWithKnowledgeBase(kb),actor);
    }

    private static final String DOCUMENT="Ontology(<urn:test:reasoning-context> Declaration(Class(<urn:test:Equipment>)))";
    private static final Instant AT=Instant.parse("2026-01-01T00:00:00Z");
    private static ReasoningOptions options() {
        return new ReasoningOptions(ReasoningRequest.AssertionScope.ACCEPTED_FACTS,ReasoningRequest.TaskKind.CLASSIFICATION,null,null);
    }
    private static Request request(String cursor) { return new Request("",Set.of(),AT,2200,cursor,options()); }
    private ReasoningResult outcome(ReasoningRequest input,ReasoningStatus status,int count) {
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),"Worker must not hold a database transaction");
        assertEquals(AT,input.validDuring().orElseThrow().fromInclusive().orElseThrow());
        var relations=new ArrayList<ReasoningResult.ClassRelation>();
        for(int i=0;i<count;i++)relations.add(new ReasoningResult.ClassRelation("urn:test:C"+i,Set.of("urn:test:Equipment")));
        return new ReasoningResult(ReasoningRequest.SCHEMA_VERSION,input.requestId(),status,input.task().kind(),
            "controlled-input-digest","controlled","1",1,relations,List.of(),List.of(),
            new ReasoningResult.Provenance(input.scope().name(),input.task().kind().name(),Instant.now(),"controlled-v1"));
    }
    @ParameterizedTest @EnumSource(ReasoningStatus.class)
    void preservesEveryOutcomeAndSuppressesConclusionsOnFailure(ReasoningStatus status) throws Exception {
        var f=fixture(DOCUMENT);
        when(worker.reason(any())).thenAnswer(call->outcome(call.getArgument(0),status,1));
        var result=contexts.context(workspace,f.actor(),f.agent(),f.graph(),request(null));
        assertEquals(status.name(),result.reasoning().outcome());
        assertEquals("controlled-input-digest",result.reasoning().inputDigest());
        assertEquals("UNAVAILABLE",result.reasoning().explanationStatus());
        String expected=switch(status) {
            case CONSISTENT,ENTAILED,NOT_ENTAILED,UNSATISFIABLE -> "COMPLETE_FOR_REQUEST";
            case RESOURCE_EXHAUSTED -> "RESOURCE_LIMIT";
            default -> status.name();
        };
        assertEquals(expected,result.reasoning().status());
        assertEquals(status==ReasoningStatus.CONSISTENT?1:0,result.reasoning().conclusions().size());
        assertTrue(result.facts().isEmpty());
        verify(worker,times(1)).reason(any());
    }
    @Test void noOptionsNeverStartsWorker() throws Exception {
        var f=fixture(DOCUMENT);
        assertEquals("NOT_RUN",contexts.context(workspace,f.actor(),f.agent(),f.graph(),
            new Request("",Set.of(),AT,2200,null)).reasoning().status());
        verifyNoInteractions(worker);
    }
    @Test void conclusionsPaginateWithoutRerunningWorkerAndCursorCannotChangeOptions() throws Exception {
        var f=fixture(DOCUMENT);
        when(worker.reason(any())).thenAnswer(call->outcome(call.getArgument(0),ReasoningStatus.CONSISTENT,80));
        var first=contexts.context(workspace,f.actor(),f.agent(),f.graph(),request(null));
        assertTrue(first.coverage().truncated());
        assertTrue(first.coverage().omittedConclusions()>0);
        assertNotNull(first.coverage().nextCursor());
        var page=first;var conclusions=new HashSet<String>();int pages=0;
        while(true) {
            assertTrue(++pages<81);
            assertEquals(first.snapshot(),page.snapshot());
            assertTrue((json.writeValueAsBytes(page).length+2)/3<=2200);
            for(var conclusion:page.reasoning().conclusions()) {
                assertEquals("INFERRED",conclusion.origin());
                assertEquals("UNAVAILABLE",conclusion.explanationStatus());
                assertTrue(conclusion.premiseAxiomIds().isEmpty());
                assertTrue(conclusion.premiseFactRevisions().isEmpty());
                assertTrue(conclusions.add(conclusion.assertion()),"No repeated conclusion");
            }
            if(page.coverage().nextCursor()==null)break;
            page=contexts.context(workspace,f.actor(),f.agent(),f.graph(),request(page.coverage().nextCursor()));
        }
        assertEquals(80,conclusions.size());
        assertEquals(0,page.coverage().omittedConclusions());
        verify(worker,times(1)).reason(any());
        String cursor=first.coverage().nextCursor();
        var altered=new Request("",Set.of(),AT,2200,cursor,new ReasoningOptions(
            ReasoningRequest.AssertionScope.TBOX_ONLY,ReasoningRequest.TaskKind.CONSISTENCY,null,null));
        assertEquals(400,assertThrows(SemanticApiException.class,()->contexts.context(workspace,f.actor(),f.agent(),f.graph(),altered)).status());
        when(wikiKnowledgeBases.findVisibleById(f.agent(),Long.valueOf(f.kb()))).thenReturn(null);
        assertEquals(404,assertThrows(SemanticApiException.class,()->contexts.context(workspace,f.actor(),f.agent(),f.graph(),request(cursor))).status());
        verify(worker,times(1)).reason(any());
    }
    @Test void continuationRevalidatesEvidenceOutsideTheRetrievedQuestionSlice() throws Exception {
        var f=fixture("Ontology(<urn:test:other-evidence> Declaration(Class(<urn:test:Equipment>)) Declaration(Class(<urn:test:Other>)) Declaration(DataProperty(<urn:test:reading>)))");
        var entity=call("POST","/graphs/"+f.graph()+"/entities","member",workspace,
            Map.of("iri","urn:test:other-machine","assertedTypes",List.of("urn:test:Other"),"displayName","Other machine"),200);
        String raw=com.baomidou.mybatisplus.core.toolkit.IdWorker.getIdStr();var now=LocalDateTime.now();
        jdbc.update("INSERT INTO mate_wiki_raw_material(id,kb_id,title,source_type,original_content,file_size,processing_status,create_time,update_time,deleted) VALUES(?,?,?,?,?,?,?,?,?,0)",
            Long.valueOf(raw),Long.valueOf(f.kb()),"Reading","text","1",1,"completed",now,now);
        String snapshot=call("POST","/graphs/"+f.graph()+"/imports","member",workspace,
            Map.of("sourceKind","WIKI_RAW","sourceRef",raw,"operationId",UUID.randomUUID().toString()),200).path("snapshotId").asText();
        String evidence=call("POST","/graphs/"+f.graph()+"/snapshots/"+snapshot+"/evidence","member",workspace,
            Map.of("operationId",UUID.randomUUID().toString(),"startCodePoint",0,"endCodePoint",1,"exactQuote","1"),200).path("id").asText();
        String statement=call("POST","/graphs/"+f.graph()+"/statements","member",workspace,
            Map.of("operationId",UUID.randomUUID().toString(),"subjectId",entity.path("id").asText(),
                "assertionText","DataPropertyAssertion(<urn:test:reading> <urn:test:other-machine> \"1\"^^<http://www.w3.org/2001/XMLSchema#integer>)",
                "validityKind","INTERVAL","validFrom","2020-01-01T00:00:00Z","evidenceIds",List.of(evidence)),200).path("id").asText();
        call("POST","/graphs/"+f.graph()+"/statements/"+statement+"/review","owner",workspace,
            Map.of("expectedRevision",1,"action","ACCEPT","reason","Verified","operationId",UUID.randomUUID().toString()),200);
        when(worker.reason(any())).thenAnswer(call->{
            ReasoningRequest input=call.getArgument(0);
            assertEquals(1,input.acceptedFacts().size(),"Reasoning must include facts outside the retrieval slice");
            return outcome(input,ReasoningStatus.CONSISTENT,80);
        });
        var first=contexts.context(workspace,f.actor(),f.agent(),f.graph(),
            new Request("",Set.of("urn:test:Equipment"),AT,2200,null,options()));
        assertTrue(first.facts().isEmpty());assertNotNull(first.coverage().nextCursor());
        long graphVersion=first.graphMutationVersion();
        jdbc.update("UPDATE mate_semantic_source_snapshot SET text_digest=? WHERE id=?","a".repeat(64),snapshot);
        assertEquals(graphVersion,jdbc.queryForObject("SELECT mutation_version FROM mate_semantic_graph WHERE id=?",Long.class,f.graph()));
        var next=new Request("",Set.of("urn:test:Equipment"),AT,2200,first.coverage().nextCursor(),options());
        assertEquals("REASONING_STALE",assertThrows(SemanticApiException.class,
            ()->contexts.context(workspace,f.actor(),f.agent(),f.graph(),next)).code());
        verify(worker,times(1)).reason(any());
    }

    @Test void oversizedReasoningStateIsRejectedBeforeItCanBecomeAContinuation() throws Exception {
        var f=fixture(DOCUMENT);
        when(worker.reason(any())).thenAnswer(call->outcome(call.getArgument(0),ReasoningStatus.CONSISTENT,3000));
        assertEquals("CONTEXT_REASONING_LIMIT",assertThrows(SemanticApiException.class,
            ()->contexts.context(workspace,f.actor(),f.agent(),f.graph(),request(null))).code());
        verify(worker,times(1)).reason(any());
    }
    @Test void boundedCacheEvictsOldContinuationsWithoutSilentlyRerunning() throws Exception {
        var f=fixture(DOCUMENT);
        when(worker.reason(any())).thenAnswer(call->outcome(call.getArgument(0),ReasoningStatus.CONSISTENT,40));
        String first=null,last=null;
        for(int i=0;i<65;i++) {
            String cursor=contexts.context(workspace,f.actor(),f.agent(),f.graph(),request(null)).coverage().nextCursor();
            assertNotNull(cursor);
            if(i==0)first=cursor;
            last=cursor;
        }
        String evicted=first;
        assertEquals("CONTEXT_STALE",assertThrows(SemanticApiException.class,
            ()->contexts.context(workspace,f.actor(),f.agent(),f.graph(),request(evicted))).code());
        assertEquals("COMPLETE_FOR_REQUEST",contexts.context(workspace,f.actor(),f.agent(),f.graph(),request(last)).reasoning().status());
        verify(worker,times(65)).reason(any());
    }

    @Test void expiredSignedContinuationCannotReuseCachedReasoning() throws Exception {
        var f=fixture(DOCUMENT);
        when(worker.reason(any())).thenAnswer(call->outcome(call.getArgument(0),ReasoningStatus.CONSISTENT,40));
        String cursor=contexts.context(workspace,f.actor(),f.agent(),f.graph(),request(null)).coverage().nextCursor();
        assertNotNull(cursor);
        // Produce a genuinely signed expired token without a ten-minute wall-clock sleep.
        var payload=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(Base64.getUrlDecoder().decode(cursor.split("\\.")[0]));
        payload.put("expiresAt",0);
        String encoded=Base64.getUrlEncoder().withoutPadding().encodeToString(json.writeValueAsBytes(payload));
        Object target=org.springframework.test.util.AopTestUtils.getTargetObject(contexts);
        byte[] key=(byte[])org.springframework.test.util.ReflectionTestUtils.getField(target,"cursorKey");
        var mac=javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(key,"HmacSHA256"));
        String expired=encoded+"."+Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertEquals("CONTEXT_STALE",assertThrows(SemanticApiException.class,
            ()->contexts.context(workspace,f.actor(),f.agent(),f.graph(),request(expired))).code());
        verify(worker,times(1)).reason(any());
    }

    @Test void graphMutationDuringWorkerRejectsResult() throws Exception {
        var f=fixture(DOCUMENT);
        when(worker.reason(any())).thenAnswer(call->{
            jdbc.update("UPDATE mate_semantic_graph SET mutation_version=mutation_version+1 WHERE id=?",f.graph());
            return outcome(call.getArgument(0),ReasoningStatus.CONSISTENT,1);
        });
        assertEquals("REASONING_STALE",assertThrows(SemanticApiException.class,
            ()->contexts.context(workspace,f.actor(),f.agent(),f.graph(),request(null))).code());
    }
    @Test void revokedAgentDuringWorkerRejectsResult() throws Exception {
        var f=fixture(DOCUMENT);
        when(worker.reason(any())).thenAnswer(call->{
            when(wikiKnowledgeBases.findVisibleById(f.agent(),Long.valueOf(f.kb()))).thenReturn(null);
            return outcome(call.getArgument(0),ReasoningStatus.CONSISTENT,1);
        });
        assertEquals(404,assertThrows(SemanticApiException.class,
            ()->contexts.context(workspace,f.actor(),f.agent(),f.graph(),request(null))).status());
    }
}
