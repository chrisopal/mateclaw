package vip.mate.semantic;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import vip.mate.semantic.support.SemanticHttpFixture;

class OntologyModelingConcurrencyIntegrationTest extends SemanticHttpFixture {
    @Test void simultaneousCreateRetriesProduceOneOntologyAndRecoverFromDatabase() throws Exception {
        String operation=UUID.randomUUID().toString();
        var body=Map.of("operationId",operation,"newOntology",Map.of("name","Concurrent model","description",""),
                "goal","Define equipment","sources",List.of());
        long before=jdbc.queryForObject("SELECT COUNT(*) FROM mate_semantic_ontology WHERE workspace_id=?",Long.class,Long.valueOf(workspace));
        var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            Callable<com.fasterxml.jackson.databind.JsonNode> create=()->{
                assertTrue(start.await(5,TimeUnit.SECONDS));
                return call("POST","/modeling-tasks","member",workspace,body,200);
            };
            var first=pool.submit(create);var second=pool.submit(create);start.countDown();
            var a=first.get(20,TimeUnit.SECONDS);var b=second.get(20,TimeUnit.SECONDS);
            assertEquals(a,b);
            assertEquals(before+1,jdbc.queryForObject("SELECT COUNT(*) FROM mate_semantic_ontology WHERE workspace_id=?",Long.class,Long.valueOf(workspace)));
            String id=a.path("id").asText();
            assertEquals(a,call("GET","/modeling-tasks/"+id,"viewer",workspace,null,200));
            assertEquals(a,json.readTree(jdbc.queryForObject("SELECT state_json FROM mate_semantic_modeling_task WHERE id=?",String.class,id)));
            var changed=new HashMap<String,Object>(body);changed.put("goal","Different goal");
            call("POST","/modeling-tasks","member",workspace,changed,409);
        }
    }

    @Test void revokedMembershipBlocksReplayAndReadImmediately() throws Exception {
        var body=Map.of("operationId",UUID.randomUUID().toString(),"newOntology",Map.of("name","Private model","description",""),
                "goal","Define equipment","sources",List.of());
        var task=call("POST","/modeling-tasks","member",workspace,body,200);
        jdbc.update("UPDATE mate_workspace_member SET deleted=1 WHERE workspace_id=? AND role='member'",Long.valueOf(workspace));
        call("POST","/modeling-tasks","member",workspace,body,403);
        call("GET","/modeling-tasks/"+task.path("id").asText(),"member",workspace,null,403);
        assertEquals(task,call("GET","/modeling-tasks/"+task.path("id").asText(),"owner",workspace,null,200));
    }
}
