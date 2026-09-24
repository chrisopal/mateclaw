package vip.mate.bidding;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.JsonNode;

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

    @Test void ownerMustBeEnabledWorkspaceMember() throws Exception {
        var outside=api("POST","/projects","owner",workspace,
            Map.of("operationId","outside","name","非法负责人","lotName","一标段","ownerId","999999999999"),400);
        assertTrue(outside.toString().contains("INVALID_OWNER"));
        String username=auth.parseToken(tokens.get("member").substring(7));
        jdbc.update("UPDATE mate_user SET enabled=FALSE WHERE username=?",username);
        api("POST","/projects","member",workspace,Map.of("operationId","disabled","name","禁用","lotName","一标段"),401);
    }

    @Test void disabledModuleDoesNotExposeBusinessResponses() throws Exception {
        biddingProperties.setEnabled(false);
        api("GET","/projects","member",workspace,null,404);
    }
}
