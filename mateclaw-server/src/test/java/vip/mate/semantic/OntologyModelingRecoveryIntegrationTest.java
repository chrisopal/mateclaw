package vip.mate.semantic;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.test.annotation.DirtiesContext;
import com.fasterxml.jackson.databind.JsonNode;
import vip.mate.semantic.support.SemanticHttpFixture;

/** Rebuilds all application beans between acceptance and response-loss recovery; H2 retains persisted rows. */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OntologyModelingRecoveryIntegrationTest extends SemanticHttpFixture {
    private static String savedWorkspace,taskId,proposalId;
    private static Map<String,String> savedTokens;
    private static Map<String,Object> decision;
    private static JsonNode accepted;

    @Test @Order(1) @DirtiesContext(methodMode=DirtiesContext.MethodMode.AFTER_METHOD)
    void commitsBeforeApplicationContextIsStopped() throws Exception {
        var task=call("POST","/modeling-tasks","member",workspace,Map.of("operationId",UUID.randomUUID().toString(),
                "newOntology",Map.of("name","Recovery model","description",""),"goal","Define equipment"),200);
        taskId=task.path("id").asText();
        var pending=call("POST","/modeling-tasks/"+taskId+"/proposals","member",workspace,Map.of(
                "operationId",UUID.randomUUID().toString(),"expectedDraftVersion",1,
                "changes",List.of(Map.of("kind","CREATE_TERM","termKind","OBJECT","clientId","equipment","name","Equipment"))),200);
        proposalId=pending.path("proposals").get(0).path("id").asText();
        decision=Map.of("operationId",UUID.randomUUID().toString(),"decision","ACCEPT");
        accepted=call("POST","/modeling-tasks/"+taskId+"/proposals/"+proposalId+"/decision","member",workspace,decision,200);
        savedWorkspace=workspace;savedTokens=Map.copyOf(tokens);
    }

    @Test @Order(2)
    void rebuiltApplicationRecoversCommittedResultWithoutReapplying() throws Exception {
        assertNotNull(accepted);
        workspace=savedWorkspace;tokens=savedTokens;
        assertEquals(accepted,call("GET","/modeling-tasks/"+taskId,"viewer",workspace,null,200));
        assertEquals(accepted,call("POST","/modeling-tasks/"+taskId+"/proposals/"+proposalId+"/decision","member",workspace,decision,200));
        var draft=call("GET","/ontologies/"+accepted.path("ontologyId").asText()+"/draft","viewer",workspace,null,200);
        assertEquals(2,draft.path("draftVersion").asLong());
        assertEquals(accepted.path("proposals").get(0).path("result").path("draft"),draft);
    }
}
