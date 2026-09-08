package vip.mate.semantic;

import org.junit.jupiter.api.Test;
import java.util.Map;
import vip.mate.semantic.support.SemanticExtractionFixture;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SemanticExtractionFeatureFlagTest extends SemanticExtractionFixture {
    @Test void disabledRejectsNewWorkAndKeepsAuthorizedHistory()throws Exception{
        String task=start();feature.setEnabled(false);
        call("POST",base+"/extraction-tasks","member",workspace,Map.of("sourceRef",raw,"modelConfigId","8","operationId",op()),409);
        assertFalse(coordinator.runNext("disabled"));verify(model,never()).extract(any());
        call("GET",base+"/extraction-tasks/"+task,"viewer",workspace,null,200);
        call("POST",base+"/extraction-tasks/"+task+"/cancel","viewer",workspace,Map.of(),403);
        assertEquals("CANCELLED",call("POST",base+"/extraction-tasks/"+task+"/cancel","member",workspace,Map.of(),200).path("status").asText());
        call("POST",base+"/entities","member",workspace,Map.of("typeKey","Equipment","displayName","Manual remains available"),200);
    }
}
