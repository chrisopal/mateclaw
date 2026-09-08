package vip.mate.semantic;

import java.util.*;
import org.junit.jupiter.api.Test;
import vip.mate.semantic.support.SemanticExtractionFixture;
import vip.mate.semantic.application.extraction.ExtractionException;
import static vip.mate.semantic.application.extraction.ExtractionContracts.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SemanticExtractionApiIntegrationTest extends SemanticExtractionFixture {
    @Test void zeroResultIsSuccessfulAndCancellationDiscardsLateModelResult()throws Exception{
        when(model.extract(any())).thenReturn(new ModelResult(List.of(),new Usage(1,0),"No supported facts"));
        String first=start();assertTrue(coordinator.runNext("zero"));
        assertEquals("SUCCEEDED",call("GET",base+"/extraction-tasks/"+first,"member",workspace,null,200).path("status").asText());
        assertEquals(0,call("GET",base+"/extraction-tasks/"+first+"/suggestions","viewer",workspace,null,200).path("total").asInt());
        String second=start();when(model.extract(any())).thenAnswer(i->{extraction.cancelActive();return new ModelResult(List.of(),new Usage(1,0),"late");});
        assertTrue(coordinator.runNext("cancel"));assertEquals("CANCELLED",call("GET",base+"/extraction-tasks/"+second,"member",workspace,null,200).path("status").asText());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM mate_semantic_extraction_suggestion WHERE task_id=?",Integer.class,second));
    }
    @Test void failedRetryIsIdempotentAndThirdAttemptIsTerminal()throws Exception{
        when(model.extract(any())).thenThrow(new ExtractionException(422,"MODEL_FORMAT"));String task=start();
        for(int attempt=1;attempt<=3;attempt++){
            assertTrue(coordinator.runNext("retry"));var read=call("GET",base+"/extraction-tasks/"+task,"member",workspace,null,200);
            assertEquals("FAILED",read.path("status").asText());assertEquals(attempt,read.path("attempts").asInt());assertEquals("MODEL_FORMAT",read.path("errorCode").asText());
            if(attempt<3){var operation=Map.of("operationId",op());call("POST",base+"/extraction-tasks/"+task+"/retry","member",workspace,operation,200);call("POST",base+"/extraction-tasks/"+task+"/retry","member",workspace,operation,200);}
        }
        call("POST",base+"/extraction-tasks/"+task+"/retry","member",workspace,Map.of("operationId",op()),409);assertFalse(coordinator.runNext("exhausted"));
    }
    @Test void snapshotExclusionAndNumericIdentifiersDoNotLeakContent()throws Exception{
        String task=start();String snapshot=call("GET",base+"/extraction-tasks/"+task,"member",workspace,null,200).path("snapshotId").asText();
        jdbc.update("INSERT INTO mate_semantic_snapshot_exclusion(graph_id,snapshot_id,actor_id,reason,created_at) VALUES(?,?,?,?,?)",graph,snapshot,"1","excluded fixture",java.time.LocalDateTime.now());
        call("GET",base+"/extraction-tasks/"+task,"viewer",workspace,null,404);
        assertEquals(0,call("GET",base+"/extraction-tasks","viewer",workspace,null,200).path("total").asInt());
        call("POST",base+"/extraction-tasks","member",workspace,Map.of("sourceRef",123,"modelConfigId","8","operationId",op()),400);
        call("GET",base+"/extraction-tasks?pageSize=101","viewer",workspace,null,400);
        assertTrue(coordinator.runNext("excluded"));assertEquals("FAILED",extraction.require(task).status().name());verify(model,never()).extract(any());
    }
}
