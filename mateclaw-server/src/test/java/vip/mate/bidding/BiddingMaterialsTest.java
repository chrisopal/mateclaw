package vip.mate.bidding;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
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
    @Autowired BiddingRepository repository;
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

    @Test void reviewerMustHaveIndependentCurrentWikiAndPageTypeAccess() throws Exception {
        var project=project();long kbId=7101,pageId=9811,writerId=7_000_000_000L+Math.floorMod(UUID.randomUUID().hashCode(),1_000_000_000),reviewerId=writerId+1;
        jdbc.update("INSERT INTO mate_agent(id,name,enabled,workspace_id,create_time,update_time,deleted) VALUES(?,?,TRUE,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0)",writerId,"Material writer",Long.valueOf(workspace));
        jdbc.update("INSERT INTO mate_agent(id,name,enabled,workspace_id,create_time,update_time,deleted) VALUES(?,?,TRUE,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0)",reviewerId,"Independent reviewer",Long.valueOf(workspace));
        ObjectNode stored=(ObjectNode)project.deepCopy();ObjectNode bindings=stored.putObject("bindings");
        bindings.putObject("writer").put("agentId",Long.toString(writerId));bindings.putObject("reviewer").put("agentId",Long.toString(reviewerId));
        jdbc.update("UPDATE mate_bidding_project SET body_json=? WHERE workspace_id=? AND id=?",json.writeValueAsString(stored),workspace,project.path("id").asText());
        WikiKnowledgeBaseEntity kb=new WikiKnowledgeBaseEntity();kb.setId(kbId);kb.setWorkspaceId(Long.valueOf(workspace));kb.setDeleted(0);
        when(knowledgeBases.findVisibleById(writerId,kbId)).thenReturn(kb);when(knowledgeBases.findVisibleById(reviewerId,kbId)).thenReturn(kb);
        WikiPageEntity page=new WikiPageEntity();page.setId(pageId);page.setKbId(kbId);page.setDeleted(0);page.setTitle("Reviewer input");page.setPageType("experience");page.setContent("Frozen source content");when(pages.getById(pageId)).thenReturn(page);
        when(pageTypePermissions.canRead(writerId,kbId,"experience")).thenReturn(true);when(pageTypePermissions.canRead(reviewerId,kbId,"experience")).thenReturn(false);
        String digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest("Frozen source content".getBytes(StandardCharsets.UTF_8)));
        var request=Map.of("operationId","bind-review-material-"+UUID.randomUUID(),"action","BIND_MATERIAL","expected",ref(project),"payload",Map.of("kind","WIKI_PAGE","knowledgeBaseId",kbId,"pageId",pageId,"expectedDigest",digest,"applicability","Review context"));
        JsonNode bound=api("POST","/projects/"+project.path("id").asText()+"/commands","member",workspace,request,200);
        BiddingTypes.Ref materialRef=json.treeToValue(bound.path("ref"),BiddingTypes.Ref.class);
        String actor=jdbc.queryForObject("SELECT id FROM mate_user WHERE username=?",String.class,auth.parseToken(tokens.get("member").substring(7)));
        BiddingTypes.Scope scope=new BiddingTypes.Scope(workspace,actor,project.path("id").asText());
        assertThrows(BiddingApiException.class,()->materials.snapshotForReviewer(scope,Long.toString(reviewerId),List.of(materialRef)));
        when(pageTypePermissions.canRead(reviewerId,kbId,"experience")).thenReturn(true);
        assertEquals("Frozen source content",materials.snapshotForReviewer(scope,Long.toString(reviewerId),List.of(materialRef)).path("items").get(0).path("content").path("content").asText());
        ObjectNode rebound=repository.findProject(workspace,project.path("id").asText());((ObjectNode)rebound.path("bindings").path("reviewer")).put("agentId",Long.toString(writerId));
        jdbc.update("UPDATE mate_bidding_project SET body_json=? WHERE id=?",json.writeValueAsString(rebound),project.path("id").asText());
        assertThrows(BiddingApiException.class,()->materials.requireReviewerReadable(scope,Long.toString(reviewerId),materialRef));
    }
}
