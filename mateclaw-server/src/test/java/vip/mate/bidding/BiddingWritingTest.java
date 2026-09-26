package vip.mate.bidding;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class BiddingWritingTest extends BiddingHttpFixture {
    @Autowired BiddingWritingService writing;

    @Test void leavesWriteIndependentlyAndLateCandidateCannotReplaceHumanEdit() throws Exception {
        var project=project();String id=project.path("id").asText(),actor=project.path("ownerId").asText();
        var outline=seedConfirmedOutline(id);
        var scope=new BiddingTypes.Scope(workspace,actor,id);
        ObjectNode before=writing.read(scope);var chapters=before.path("chapters");assertEquals(2,chapters.size());
        var firstGuard=json.treeToValue(chapters.get(0).path("editExpectedRef"),BiddingTypes.Ref.class);
        var secondGuard=json.treeToValue(chapters.get(1).path("editExpectedRef"),BiddingTypes.Ref.class);
        BiddingTypes.Ref first=writing.accept(claim(scope,outline,"c1",firstGuard),result("c1"));
        BiddingTypes.Ref second=writing.accept(claim(scope,outline,"c2",secondGuard),result("c2"));
        assertNotNull(first);assertNotNull(second);assertNotEquals(first.id(),second.id());
        var editPayload=json.createObjectNode().put("chapterId","c1");editPayload.putArray("blocks").addObject().put("type","paragraph").put("text","Human edit");
        editPayload.putArray("responses");editPayload.putArray("citations");editPayload.putArray("missingMaterials");editPayload.putArray("unresolvedItems");
        var edited=writing.edit(scope,new BiddingTypes.Command("human-edit-c1",firstGuard,"EDIT_CHAPTER",editPayload));
        var humanRef=json.treeToValue(edited.path("ref"),BiddingTypes.Ref.class);
        BiddingApiException late=assertThrows(BiddingApiException.class,()->writing.accept(claim(scope,outline,"c1",firstGuard),result("c1")));
        assertEquals("CHAPTER_STALE",late.code());
        ObjectNode adoptPayload=json.createObjectNode().put("chapterId","c1");adoptPayload.set("candidateRef",json.valueToTree(first));
        var retryAdopt=new BiddingTypes.Command("adopt-late-candidate",humanRef,"ADOPT_CHAPTER",adoptPayload);
        BiddingApiException stale=assertThrows(BiddingApiException.class,()->writing.adopt(scope,retryAdopt));
        assertEquals(409,stale.status());
        assertEquals(humanRef,json.treeToValue(writing.read(scope).path("chapters").get(0).path("editExpectedRef"),BiddingTypes.Ref.class));
    }

    private BiddingTypes.Claim claim(BiddingTypes.Scope scope,BiddingTypes.Ref outline,String chapter,BiddingTypes.Ref guard){
        ObjectNode input=json.createObjectNode().set("outlineRef",json.valueToTree(outline));input.put("chapterId",chapter);input.set("_biddingHeadGuard",json.valueToTree(guard));
        return new BiddingTypes.Claim(scope,"task-"+chapter,"attempt-"+chapter,"token-"+chapter,1,1,Instant.now(),"agent",
                new BiddingTypes.SkillPin("bidding-technical-writing","1","digest",Map.of()),"model","config",List.of(outline),input);
    }
    private ObjectNode result(String chapter){ObjectNode out=json.createObjectNode().put("schemaVersion","1");ObjectNode body=out.putObject("chapter").put("chapterId",chapter);body.putArray("blocks").addObject().put("type","paragraph").put("text","Evidence pending");out.putArray("responses");out.putArray("citations");out.putArray("missingMaterials");out.putArray("unresolvedItems");out.putArray("warnings");return out;}
    private BiddingTypes.Ref seedConfirmedOutline(String projectId)throws Exception{
        var scope=new BiddingTypes.Scope(workspace,"1",projectId);
        var sourceSet=new BiddingTypes.Ref("sourceSet","current",1,"set-digest");
        jdbc.update("INSERT INTO mate_bidding_revision(id,workspace_id,project_id,kind,object_id,version,payload_json,input_refs_json,status,digest,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",UUID.randomUUID().toString(),workspace,projectId,"sourceSet","current",1,"{}","[]","CONFIRMED",sourceSet.digest(),Timestamp.from(Instant.now()));
        jdbc.update("INSERT INTO mate_bidding_head(workspace_id,project_id,kind,object_id,version,selected_ref_json) VALUES(?,?,?,?,?,?)",workspace,projectId,"sourceSet","current",1,json.writeValueAsString(sourceSet));
        var baseline=new BiddingTypes.Ref("analysisBaseline","current",1,"base-digest");ObjectNode base=json.createObjectNode().put("schemaVersion","1");
        jdbc.update("INSERT INTO mate_bidding_revision(id,workspace_id,project_id,kind,object_id,version,payload_json,input_refs_json,status,digest,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",UUID.randomUUID().toString(),workspace,projectId,baseline.kind(),baseline.id(),1,json.writeValueAsString(base),json.writeValueAsString(List.of(sourceSet)),"CONFIRMED",baseline.digest(),Timestamp.from(Instant.now()));
        jdbc.update("INSERT INTO mate_bidding_head(workspace_id,project_id,kind,object_id,version,selected_ref_json) VALUES(?,?,?,?,?,?)",workspace,projectId,baseline.kind(),baseline.id(),1,json.writeValueAsString(baseline));
        ObjectNode p=json.createObjectNode().put("schemaVersion","1");var cs=p.putArray("chapters");for(int i=1;i<=2;i++){ObjectNode c=cs.addObject().put("id","c"+i).putNull("parentId").put("order",i).put("title","Chapter "+i);c.putArray("requirementRefs");c.putArray("scoringRefs");c.putArray("materialRefs");c.putArray("mandatoryOutlineRefs");}
        var outline=new BiddingTypes.Ref("outline","current",1,"outline-digest");jdbc.update("INSERT INTO mate_bidding_revision(id,workspace_id,project_id,kind,object_id,version,payload_json,input_refs_json,status,digest,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",UUID.randomUUID().toString(),workspace,projectId,outline.kind(),outline.id(),1,json.writeValueAsString(p),json.writeValueAsString(List.of(baseline)),"CONFIRMED",outline.digest(),Timestamp.from(Instant.now()));
        jdbc.update("INSERT INTO mate_bidding_head(workspace_id,project_id,kind,object_id,version,selected_ref_json) VALUES(?,?,?,?,?,?)",workspace,projectId,outline.kind(),outline.id(),1,json.writeValueAsString(outline));return outline;
    }
}
