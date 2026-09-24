package vip.mate.bidding;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.JsonNode;
import vip.mate.auth.model.UserEntity;

class BiddingProjectTest extends BiddingHttpFixture {
    @Test void cannotReadAnotherWorkspaceOrCreateAsViewer() throws Exception {
        var p = project();
        api("GET", "/projects/" + p.path("id").asText(), "owner", otherWorkspace, null, 404);
        api("POST", "/projects", "viewer", workspace,
                Map.of("operationId", "denied", "name", "测试标段", "lotName", "一标段"), 403);
        api("GET", "/projects", "member", null, null, 400);
    }

    @Test void createIsIdempotentAndPayloadConflictIsRejected() throws Exception {
        var body = Map.of("operationId", "same", "name", "测试项目", "lotName", "一标段");
        JsonNode first = api("POST", "/projects", "member", workspace, body, 200);
        assertEquals(first, api("POST", "/projects", "member", workspace, body, 200));
        api("POST", "/projects", "member", workspace,
                Map.of("operationId", "same", "name", "另一个项目", "lotName", "一标段"), 409);
    }

    @Test void projectUpdatesUseOwnerIdentityAndVersionCas() throws Exception {
        JsonNode p=project();
        Map<String,Object> body=Map.of("operationId","project-update","expected",ref(p),"action","UPDATE_PROJECT","payload",Map.of("name","更新后的项目"));
        JsonNode first=api("POST","/projects/"+p.path("id").asText()+"/commands","member",workspace,body,200);
        assertEquals("更新后的项目",first.path("result").path("name").asText());
        assertEquals(first,api("POST","/projects/"+p.path("id").asText()+"/commands","member",workspace,body,200));
        var conflict=new java.util.HashMap<>(body); conflict.put("payload",Map.of("name","冲突内容"));
        api("POST","/projects/"+p.path("id").asText()+"/commands","member",workspace,conflict,409);
        JsonNode reread=api("GET","/projects/"+p.path("id").asText(),"member",workspace,null,200);
        assertEquals(first.path("result"),reread);
        command(p,ref(p),"UPDATE_PROJECT",Map.of("name","过期修改"),"member",409);
    }

    @Test void archiveRejectsNewMutationsButReplaysTheSameOperation() throws Exception {
        JsonNode p=project();
        Map<String,Object> archive=Map.of("operationId","archive-op","expected",ref(p),"action","ARCHIVE_PROJECT","payload",Map.of());
        String path="/projects/"+p.path("id").asText()+"/commands";
        JsonNode first=api("POST",path,"member",workspace,archive,200);
        assertEquals("ARCHIVED",first.path("result").path("stage").asText());
        assertEquals(first,api("POST",path,"member",workspace,archive,200));
        Map<String,Object> update=Map.of("operationId","new-after-archive","expected",ref(first.path("result")),"action","UPDATE_PROJECT","payload",Map.of("name","不应发生"));
        var error=api("POST",path,"member",workspace,update,409);
        assertTrue(error.toString().contains("PROJECT_ARCHIVED"));
    }

    @Test void concurrentSameOperationCreatesOnlyOneProject() throws Exception {
        String op="parallel-"+java.util.UUID.randomUUID();
        Map<String,String> body=Map.of("operationId",op,"name","并发项目","lotName","一标段");
        CountDownLatch start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var one=pool.submit(()->{ start.await(); return api("POST","/projects","member",workspace,body,200); });
            var two=pool.submit(()->{ start.await(); return api("POST","/projects","member",workspace,body,200); });
            start.countDown(); JsonNode p1=one.get(),p2=two.get();
            assertEquals(p1,p2);
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM mate_bidding_project WHERE workspace_id=?",Integer.class,workspace));
        }
    }

    @Test void ownerMustBeEnabledWorkspaceMember() throws Exception {
        UserEntity outsider=new UserEntity(); outsider.setUsername("bidding_outsider_"+java.util.UUID.randomUUID());
        outsider.setPassword("unused"); outsider.setRole("user"); outsider.setDeleted(0); auth.createUser(outsider);
        var outside=api("POST","/projects","owner",workspace,
            Map.of("operationId","outside","name","非法负责人","lotName","一标段","ownerId",outsider.getId().toString()),400);
        assertTrue(outside.toString().contains("INVALID_OWNER"));
        UserEntity disabledOwner=new UserEntity(); disabledOwner.setUsername("bidding_disabled_owner_"+java.util.UUID.randomUUID());
        disabledOwner.setPassword("unused"); disabledOwner.setRole("user"); disabledOwner.setDeleted(0); disabledOwner.setEnabled(true); auth.createUser(disabledOwner);
        workspaces.addMember(Long.valueOf(workspace),disabledOwner.getId(),"member");
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM mate_workspace_member m JOIN mate_user u ON u.id=m.user_id WHERE m.workspace_id=? AND m.user_id=? AND m.role='member' AND m.deleted=0 AND u.enabled=TRUE AND u.deleted=0",Integer.class,Long.valueOf(workspace),disabledOwner.getId()));
        jdbc.update("UPDATE mate_user SET enabled=FALSE WHERE id=?",disabledOwner.getId());
        var disabled=api("POST","/projects","owner",workspace,
            Map.of("operationId","disabled-owner","name","禁用负责人","lotName","一标段","ownerId",disabledOwner.getId().toString()),400);
        assertTrue(disabled.toString().contains("INVALID_OWNER"));
        String username=auth.parseToken(tokens.get("member").substring(7));
        jdbc.update("UPDATE mate_user SET enabled=FALSE WHERE username=?",username);
        api("POST","/projects","member",workspace,Map.of("operationId","disabled","name","禁用","lotName","一标段"),401);
    }

    @Test void disabledModuleDoesNotExposeBusinessResponses() throws Exception {
        biddingProperties.setEnabled(false);
        api("GET","/projects","member",workspace,null,404);
    }
}
