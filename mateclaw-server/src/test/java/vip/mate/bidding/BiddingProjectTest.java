package vip.mate.bidding;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.JsonNode;
import vip.mate.auth.model.UserEntity;

class BiddingProjectTest extends BiddingHttpFixture {
    @Test void projectOwnerApprovalCapabilityIsScopedToAuthorizedProject() throws Exception {
        JsonNode project = project();
        String path = "/projects/" + project.path("id").asText();
        JsonNode ownerView = api("GET", path, "member", workspace, null, 200);
        assertTrue(ownerView.path("capabilities").path("canApprove").asBoolean());

        UserEntity secondMember = new UserEntity();
        String username = "bidding_second_member_" + java.util.UUID.randomUUID();
        String password = java.util.UUID.randomUUID().toString();
        secondMember.setUsername(username); secondMember.setPassword(password); secondMember.setRole("user"); secondMember.setDeleted(0);
        auth.createUser(secondMember);
        workspaces.addMember(Long.valueOf(workspace), secondMember.getId(), "member");
        var login = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/auth/login")
                .contentType("application/json").content(json.writeValueAsString(Map.of("username", username, "password", password))))
                .andReturn().getResponse();
        assertEquals(200, login.getStatus(), login.getContentAsString());
        tokens.put("secondMember", "Bearer " + json.readTree(login.getContentAsString()).path("data").path("token").asText());

        JsonNode otherMemberView = api("GET", path, "secondMember", workspace, null, 200);
        assertFalse(otherMemberView.path("capabilities").path("canApprove").asBoolean());
        JsonNode viewerView = api("GET", path, "viewer", workspace, null, 200);
        assertFalse(viewerView.path("capabilities").path("canApprove").asBoolean());

        Map<String,Object> update = Map.of("operationId", "owner-approval", "expected", ref(project), "action", "UPDATE_PROJECT",
                "payload", Map.of("name", "负责人已批准"));
        api("POST", path + "/commands", "secondMember", workspace, update, 403);
        api("POST", path + "/commands", "viewer", workspace, update, 403);
        JsonNode accepted = api("POST", path + "/commands", "member", workspace, update, 200);
        assertEquals("负责人已批准", accepted.path("result").path("name").asText());

        JsonNode coarse = api("GET", "/capabilities", "member", workspace, null, 200);
        assertFalse(coarse.path("canApprove").asBoolean(), "workspace capability remains coarse and does not infer project ownership");
    }

    @Test void viewerProjectOwnerDoesNotReceiveApprovalCapability() throws Exception {
        String viewerUsername=auth.parseToken(tokens.get("viewer").substring(7));
        String viewerId=jdbc.queryForObject("SELECT id FROM mate_user WHERE username=?",String.class,viewerUsername);
        JsonNode project=api("POST","/projects","member",workspace,
                Map.of("operationId","viewer-owned","name","Viewer owned","lotName","Lot A","ownerId",viewerId),200);
        JsonNode viewerView=api("GET","/projects/"+project.path("id").asText(),"viewer",workspace,null,200);
        assertFalse(viewerView.path("capabilities").path("canApprove").asBoolean());
    }

    @Test void globalAdminApprovalCapabilityMatchesCommandAuthorization() throws Exception {
        JsonNode project=project();
        String username="bidding_global_admin_"+java.util.UUID.randomUUID();
        String password=java.util.UUID.randomUUID().toString();
        UserEntity admin=new UserEntity(); admin.setUsername(username); admin.setPassword(password); admin.setRole("admin"); admin.setDeleted(0);
        auth.createUser(admin);
        var login=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/auth/login")
                .contentType("application/json").content(json.writeValueAsString(Map.of("username",username,"password",password))))
                .andReturn().getResponse();
        assertEquals(200,login.getStatus(),login.getContentAsString());
        tokens.put("globalAdmin","Bearer "+json.readTree(login.getContentAsString()).path("data").path("token").asText());

        JsonNode adminView=api("GET","/projects/"+project.path("id").asText(),"globalAdmin",workspace,null,200);
        assertTrue(adminView.path("capabilities").path("canApprove").asBoolean());
        assertTrue(api("GET","/capabilities","globalAdmin",workspace,null,200).path("canApprove").asBoolean());
        JsonNode updated=api("POST","/projects/"+project.path("id").asText()+"/commands","globalAdmin",workspace,
                Map.of("operationId","global-admin-approval","expected",ref(project),"action","UPDATE_PROJECT",
                        "payload",Map.of("name","Admin approved")),200);
        assertEquals("Admin approved",updated.path("result").path("name").asText());
    }

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
        assertEquals(first.path("result").path("ref"),reread.path("ref"));
        assertEquals(first.path("result").path("name"),reread.path("name"));
        command(p,ref(p),"UPDATE_PROJECT",Map.of("name","过期修改"),"member",409);
    }

    @Test void projectUpdatesPreserveEmployeeBindingsAndKeepReferenceUsable() throws Exception {
        JsonNode created = project();
        String id = created.path("id").asText();
        var stored = (com.fasterxml.jackson.databind.node.ObjectNode) created.deepCopy();
        var writer = stored.putObject("bindings").putObject("writer");
        writer.put("agentId", "412"); writer.put("configDigest", "a".repeat(64));
        stored.putObject("selectedRefs").putObject("outline").put("version", 3);
        jdbc.update("UPDATE mate_bidding_project SET body_json=? WHERE workspace_id=? AND id=?", json.writeValueAsString(stored), workspace, id);

        JsonNode updated = command(stored, ref(stored), "UPDATE_PROJECT", Map.of("name", "更新员工绑定后的项目"), "member", 200);
        JsonNode result = updated.path("result");
        assertEquals("412", result.path("bindings").path("writer").path("agentId").asText());
        assertEquals("a".repeat(64), result.path("bindings").path("writer").path("configDigest").asText());
        assertEquals(3, result.path("selectedRefs").path("outline").path("version").asInt());

        JsonNode second = command(result, ref(result), "UPDATE_PROJECT", Map.of("lotName", "二标段"), "member", 200);
        assertEquals("412", second.path("result").path("bindings").path("writer").path("agentId").asText());
        assertEquals(3, second.path("result").path("version").asInt());
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
