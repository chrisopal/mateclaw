package vip.mate.bidding;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import vip.mate.wiki.model.WikiKnowledgeBaseEntity;
import vip.mate.wiki.model.WikiPageEntity;
import vip.mate.wiki.service.WikiKnowledgeBaseService;
import vip.mate.wiki.service.WikiPageService;
import vip.mate.wiki.service.WikiPageTypePermissionService;

class BiddingMaterialsTest extends BiddingHttpFixture {
    @Autowired WikiKnowledgeBaseService knowledgeBases;
    @Autowired BiddingMaterials materials;
    @MockBean WikiPageService pages;
    @MockBean WikiPageTypePermissionService pageTypePermissions;

    @Test void bindsFixedWikiRevisionAndRechecksOriginAuthorization() throws Exception {
        var project=project();
        long kbId=7021, pageId=9811, agentId=9813;
        jdbc.update("INSERT INTO mate_agent(id,name,enabled,workspace_id,create_time,update_time,deleted) VALUES(?,?,TRUE,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0)",agentId,"Material writer",Long.valueOf(workspace));
        var storedProject=(com.fasterxml.jackson.databind.node.ObjectNode)project.deepCopy();
        storedProject.putObject("bindings").putObject("writer").put("agentId",Long.toString(agentId));
        jdbc.update("UPDATE mate_bidding_project SET body_json=? WHERE workspace_id=? AND id=?",json.writeValueAsString(storedProject),workspace,project.path("id").asText());
        WikiKnowledgeBaseEntity kb=new WikiKnowledgeBaseEntity(); kb.setId(kbId); kb.setWorkspaceId(Long.valueOf(workspace)); kb.setDeleted(0);
        when(knowledgeBases.findVisibleById(agentId,kbId)).thenReturn(kb);
        WikiPageEntity page=new WikiPageEntity(); page.setId(pageId); page.setKbId(kbId); page.setDeleted(0); page.setTitle("选定案例"); page.setPageType("experience"); page.setContent("固定内容 v1");
        when(pages.getById(pageId)).thenReturn(page);
        String digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest("固定内容 v1".getBytes(StandardCharsets.UTF_8)));
        Map<String,Object> request=new java.util.LinkedHashMap<>();
        request.put("operationId","bind-"+UUID.randomUUID()); request.put("action","BIND_MATERIAL");
        request.put("payload",Map.of("kind","WIKI_PAGE","knowledgeBaseId",kbId,"pageId",pageId,"expectedDigest",digest,"applicability","作为本项目能力参考"));
        request.put("expected",ref(project));
        String commandPath="/projects/"+project.path("id").asText()+"/commands";
        when(pageTypePermissions.canRead(agentId,kbId,"experience")).thenReturn(false);
        api("POST",commandPath,"member",workspace,request,403);
        when(pageTypePermissions.canRead(agentId,kbId,"experience")).thenReturn(true);
        JsonNode result=api("POST",commandPath,"member",workspace,request,200);
        assertEquals(digest,result.path("ref").path("digest").asText());
        assertEquals("固定内容 v1",result.path("content").path("content").asText());
        when(pageTypePermissions.canRead(agentId,kbId,"experience")).thenReturn(false);
        api("POST",commandPath,"member",workspace,request,403);
        when(pageTypePermissions.canRead(agentId,kbId,"experience")).thenReturn(true);
        assertEquals(result,api("POST",commandPath,"member",workspace,request,200));
        String actorId=jdbc.queryForObject("SELECT id FROM mate_user WHERE username=?",String.class,auth.parseToken(tokens.get("member").substring(7)));
        var stored=materials.snapshot(new BiddingTypes.Scope(workspace,actorId,project.path("id").asText()),Long.toString(agentId),
                java.util.List.of(json.treeToValue(result.path("ref"),BiddingTypes.Ref.class)));
        assertEquals("固定内容 v1",stored.path("items").get(0).path("content").path("content").asText());

        when(pageTypePermissions.canRead(agentId,kbId,"experience")).thenReturn(false);
        assertThrows(BiddingApiException.class,()->materials.snapshot(new BiddingTypes.Scope(workspace,actorId,project.path("id").asText()),Long.toString(agentId),
                java.util.List.of(json.treeToValue(result.path("ref"),BiddingTypes.Ref.class))));
        var rows=api("GET","/projects/"+project.path("id").asText()+"/materials","member",workspace,null,200).path("items");
        assertEquals("UNAVAILABLE",rows.get(0).path("validity").asText());
        assertFalse(rows.get(0).has("title"));

        when(pageTypePermissions.canRead(agentId,kbId,"experience")).thenReturn(true);
        when(knowledgeBases.findVisibleById(agentId,kbId)).thenReturn(null);
        rows=api("GET","/projects/"+project.path("id").asText()+"/materials","member",workspace,null,200).path("items");
        assertEquals("UNAVAILABLE",rows.get(0).path("validity").asText());
        assertFalse(rows.get(0).has("title"),"revoked material metadata must not leak through a copied snapshot");
        assertThrows(BiddingApiException.class,()->materials.snapshot(new BiddingTypes.Scope(workspace,actorId,project.path("id").asText()),Long.toString(agentId),
                java.util.List.of(json.treeToValue(result.path("ref"),BiddingTypes.Ref.class))));

        when(knowledgeBases.findVisibleById(agentId,kbId)).thenReturn(kb);
        for (String invalidState : java.util.List.of("disabled", "deleted", "moved")) {
            switch (invalidState) {
                case "disabled" -> jdbc.update("UPDATE mate_agent SET enabled=FALSE WHERE id=?",agentId);
                case "deleted" -> jdbc.update("UPDATE mate_agent SET enabled=TRUE,deleted=1 WHERE id=?",agentId);
                case "moved" -> jdbc.update("UPDATE mate_agent SET enabled=TRUE,deleted=0,workspace_id=? WHERE id=?",Long.valueOf(workspace)+1,agentId);
            }
            assertThrows(BiddingApiException.class,()->materials.snapshot(new BiddingTypes.Scope(workspace,actorId,project.path("id").asText()),Long.toString(agentId),
                    java.util.List.of(json.treeToValue(result.path("ref"),BiddingTypes.Ref.class))),invalidState);
        }
    }
}
