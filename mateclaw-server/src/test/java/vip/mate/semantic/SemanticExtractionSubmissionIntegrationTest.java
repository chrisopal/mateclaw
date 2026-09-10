package vip.mate.semantic;

import org.junit.jupiter.api.Test;
import java.util.*;
import vip.mate.semantic.support.SemanticExtractionFixture;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SemanticExtractionSubmissionIntegrationTest extends SemanticExtractionFixture {
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean vip.mate.semantic.extraction.MateClawSubmissionAdapter submissionAdapter;
    @Test void reviewRequiresExplicitObjectAndCasThenProposesExactlyOnce()throws Exception{
        var suggestion=generate();String id=suggestion.path("id").asText();
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM mate_semantic_statement WHERE graph_id=?",Integer.class,graph));
        call("POST",base+"/suggestions/"+id+"/submit","member",workspace,Map.of("expectedVersion",1,"operationId",op()),422);
        var edited=call("PATCH",base+"/suggestions/"+id,"member",workspace,edit(1),200);assertTrue(edited.path("version").isNumber());
        call("PATCH",base+"/suggestions/"+id,"member",workspace,edit(1),409);
        var command=Map.of("expectedVersion",2,"operationId",op());var result=call("POST",base+"/suggestions/"+id+"/submit","member",workspace,command,200);
        assertEquals(result,call("POST",base+"/suggestions/"+id+"/submit","member",workspace,command,200));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM mate_semantic_statement WHERE graph_id=?",Integer.class,graph));
        assertEquals("PROPOSED",jdbc.queryForObject("SELECT review_status FROM mate_semantic_statement_revision WHERE statement_id=?",String.class,result.path("statementId").asText()));
        call("PATCH",base+"/suggestions/"+id,"member",workspace,edit(2),409);
        call("POST",base+"/suggestions/"+id+"/submit","member",workspace,Map.of("expectedVersion",2,"operationId",op()),409);
    }
    @Test void committedProposalRecoversLostReceiptAndOnlyOwnerCanApprove()throws Exception{
        var suggestion=generate();String id=suggestion.path("id").asText();
        var edit=edit(1);call("PATCH",base+"/suggestions/"+id,"member",workspace,edit,200);
        assertEquals(2,call("PATCH",base+"/suggestions/"+id,"member",workspace,edit,200).path("version").asInt(),"edit operation replays without creating a third revision");
        var altered=new HashMap<>(edit);altered.put("assertionText",assertion(SUBJECT_IRI).replace("\"220\"", "\"221\""));call("PATCH",base+"/suggestions/"+id,"member",workspace,altered,409);
        var fail=new java.util.concurrent.atomic.AtomicBoolean(true);
        doAnswer(invocation->{if(fail.getAndSet(false))throw new IllegalStateException("injected receipt storage outage");return invocation.callRealMethod();}).when(extraction).saveReceipt(any(),anyString(),any());
        String operation=op();var command=Map.of("expectedVersion",2,"operationId",operation);
        call("POST",base+"/suggestions/"+id+"/submit","member",workspace,command,500);
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM mate_semantic_statement WHERE graph_id=?",Integer.class,graph));
        String statement=jdbc.queryForObject("SELECT id FROM mate_semantic_statement WHERE graph_id=?",String.class,graph);
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM mate_semantic_extraction_receipt WHERE graph_id=?",Integer.class,graph));
        call("PATCH",base+"/suggestions/"+id,"member",workspace,edit(2),409);
        var recovered=call("POST",base+"/suggestions/"+id+"/submit","member",workspace,command,200);assertEquals(statement,recovered.path("statementId").asText());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM mate_semantic_extraction_receipt WHERE graph_id=?",Integer.class,graph));
        assertEquals(0,call("GET",base+"/statements","viewer",workspace,null,200).path("total").asInt());
        var review=Map.of("expectedRevision",1,"action","ACCEPT","reason","Reviewed exact fixture evidence","operationId",op());
        call("POST",base+"/statements/"+statement+"/review","member",workspace,review,403);
        call("POST",base+"/statements/"+statement+"/review","owner",workspace,review,200);
        assertEquals(statement,call("GET",base+"/statements","viewer",workspace,null,200).path("items").get(0).path("id").asText());
        call("POST",base+"/sources/withdraw","owner",workspace,Map.of("sourceKind","WIKI_RAW","sourceRef",raw,"reason","fixture withdrawn","operationId",op()),200);
        assertEquals(0,call("GET",base+"/statements","viewer",workspace,null,200).path("total").asInt());
    }
    @Test void bindingChangeBetweenApplicationCheckAndProposeIsRejected()throws Exception{
        String ontology=create();var draft=save(ontology,draft(ontology).path("draftVersion").asLong());var replacement=publish(ontology,draft.path("draftVersion").asLong(),op()).path("id").asText();
        var suggestion=generate();String id=suggestion.path("id").asText();call("PATCH",base+"/suggestions/"+id,"member",workspace,edit(1),200);
        doAnswer(invocation->{jdbc.update("UPDATE mate_semantic_graph SET ontology_revision_id=? WHERE id=?",replacement,graph);return invocation.callRealMethod();}).when(submissionAdapter).submit(any(),anyString(),any(),any(),any(),anyString());
        call("POST",base+"/suggestions/"+id+"/submit","member",workspace,Map.of("expectedVersion",2,"operationId",op()),409);
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM mate_semantic_statement WHERE graph_id=?",Integer.class,graph));
    }
    @Test void forgedQuoteAndWrongGraphEntityCannotSubmit()throws Exception{
        var suggestion=generate();String id=suggestion.path("id").asText();var invalid=edit(1);invalid.put("quotes",List.of(Map.of("startCodePoint",0,"endCodePoint",1,"exactQuote","伪造")));
        var edited=call("PATCH",base+"/suggestions/"+id,"member",workspace,invalid,200);assertFalse(edited.path("diagnostics").isEmpty());
        call("POST",base+"/suggestions/"+id+"/submit","member",workspace,Map.of("expectedVersion",2,"operationId",op()),422);
        var wrong=edit(2);wrong.put("subjectId","999999999999");
        call("PATCH",base+"/suggestions/"+id,"member",workspace,wrong,422);
    }
}
