package vip.mate.semantic;

import org.junit.jupiter.api.Test;
import java.util.*;
import vip.mate.semantic.support.SemanticExtractionFixture;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SemanticExtractionReadinessIntegrationTest extends SemanticExtractionFixture {
    private com.fasterxml.jackson.databind.JsonNode capabilities() throws Exception {
        return call("GET", base+"/extraction-capabilities", "viewer", workspace, null, 200);
    }
    @Test void capabilityExplainsIndependentConditionsAndNoTaskIsInserted() throws Exception {
        var ready=capabilities();assertTrue(ready.path("canStart").asBoolean());
        assertTrue(ready.path("ontologyVersion").asInt()>0);assertFalse(ready.path("ontologyName").asText().isBlank());
        feature.setEnabled(false);doReturn(false).when(scheduler).enabled();when(model.models()).thenReturn(List.of());
        var binding=call("GET","/knowledge-bases/"+kb+"/binding","owner",workspace,null,200);
        call("PUT","/knowledge-bases/"+kb+"/binding","owner",workspace,Map.of("action","DISABLE","expectedGraphVersion",binding.path("graphVersion").asLong()),200);
        var cap=capabilities();assertFalse(cap.path("canStart").asBoolean());
        var reasons=cap.path("unavailableReasons").toString();
        for(var reason:List.of("EXTRACTION_DISABLED","SCHEDULER_DISABLED","BINDING_DISABLED","MODEL_UNAVAILABLE"))assertTrue(reasons.contains(reason),reasons);
        call("POST",base+"/extraction-tasks","member",workspace,Map.of("sourceRef",raw,"modelConfigId","8","operationId",op()),409);
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM mate_semantic_extraction_task WHERE graph_id=?",Integer.class,graph));
    }
    @Test void selectedVersionMustStillMatchAndValidStartPinsIt() throws Exception {
        var revision=capabilities().path("ontologyRevisionId").asText();
        var command=new HashMap<String,Object>(Map.of("sourceRef",raw,"modelConfigId","8","operationId",op(),"expectedOntologyRevisionId","different"));
        var rejected=call("POST",base+"/extraction-tasks","member",workspace,command,409);
        assertEquals("ONTOLOGY_REVISION_CONFLICT",rejected.path("code").asText());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM mate_semantic_extraction_task WHERE graph_id=?",Integer.class,graph));
        command.put("expectedOntologyRevisionId",revision);
        var first=call("POST",base+"/extraction-tasks","member",workspace,command,202);
        assertEquals(revision,first.path("ontologyRevisionId").asText());
        assertEquals(first.path("id"),call("POST",base+"/extraction-tasks","member",workspace,command,202).path("id"));
        assertTrue(coordinator.runNext("readiness"));
        assertEquals("SUCCEEDED",call("GET",base+"/extraction-tasks/"+first.path("id").asText(),"viewer",workspace,null,200).path("status").asText());
    }
    @Test void schedulerDisabledDoesNotQueueNewWorkAndHistoryCanBeCancelled() throws Exception {
        var task=start();doReturn(false).when(scheduler).enabled();
        call("POST",base+"/extraction-tasks","member",workspace,Map.of("sourceRef",raw,"modelConfigId","8","operationId",op()),409);
        assertEquals("CANCELLED",call("POST",base+"/extraction-tasks/"+task+"/cancel","member",workspace,Map.of(),200).path("status").asText());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM mate_semantic_extraction_task WHERE graph_id=?",Integer.class,graph));
    }
    @Test void disabledBindingKeepsHistoryAndCancellationAvailable() throws Exception {
        var task=start();
        var binding=call("GET","/knowledge-bases/"+kb+"/binding","owner",workspace,null,200);
        call("PUT","/knowledge-bases/"+kb+"/binding","owner",workspace,Map.of("action","DISABLE","expectedGraphVersion",binding.path("graphVersion").asLong()),200);
        assertFalse(capabilities().path("canStart").asBoolean());
        call("GET",base+"/extraction-tasks/"+task,"viewer",workspace,null,200);
        call("POST",base+"/extraction-tasks/"+task+"/cancel","viewer",workspace,Map.of(),403);
        assertEquals("CANCELLED",call("POST",base+"/extraction-tasks/"+task+"/cancel","member",workspace,Map.of(),200).path("status").asText());
    }

}
