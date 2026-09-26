package vip.mate.bidding;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Map;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BiddingOutlineTest extends BiddingHttpFixture {
    @org.springframework.beans.factory.annotation.Autowired BiddingDependencies dependencies;
    @org.springframework.beans.factory.annotation.Autowired BiddingOutlineService outlines;
    @Test void outlineReadIsScopedAndFirstEditRequiresServerIssuedEmptyHeadAndConfirmedBaseline() throws Exception {
        var project=project(); String id=project.path("id").asText();
        var outline=api("GET","/projects/"+id+"/outline","viewer",workspace,null,200);
        assertEquals(0,outline.path("editExpectedRef").path("version").asInt());
        assertEquals("outline",outline.path("editExpectedRef").path("kind").asText());
        assertTrue(outline.path("candidates").isEmpty());
        assertFalse(outline.has("confirmed"));
        api("GET","/projects/"+id+"/outline","viewer",otherWorkspace,null,403);
        api("POST","/projects/"+id+"/commands","viewer",workspace,
                Map.of("operationId","viewer-outline-save","expected",outline.path("editExpectedRef"),"action","SAVE_OUTLINE","payload",Map.of("payload",Map.of())),403);
        var notConfirmed=api("POST","/projects/"+id+"/commands","member",workspace,
                Map.of("operationId","outline-before-baseline","expected",outline.path("editExpectedRef"),"action","SAVE_OUTLINE","payload",Map.of("payload",Map.of("schemaVersion","1","chapters",java.util.List.of(),"unmappedItems",java.util.List.of(),"warnings",java.util.List.of()))),409);
        assertTrue(notConfirmed.toString().contains("BASELINE_NOT_CONFIRMED"));
        var reread=api("GET","/projects/"+id+"/outline","owner",workspace,null,200);
        assertEquals(outline.path("editExpectedRef"),reread.path("editExpectedRef"));
    }

    @Test void confirmedBaselineRequiresEveryMandatoryAndTechnicalItemOnLeafChapter() throws Exception {
        var project=project(); String id=project.path("id").asText();
        var baselineFixture=seedBaseline(project,true); var baseline=baselineFixture.ref(); var baselinePayload=baselineFixture.payload();
        jdbc.update("INSERT INTO mate_bidding_decision(id,workspace_id,project_id,target_ref_json,decision,reason,actor_id,created_at) VALUES(?,?,?,?,?,?,?,?)",
                "analysis-opt-in",workspace,id,json.writeValueAsString(baseline),"CONFIRM_ANALYSIS","{\"autoPlanOutline\":true}",project.path("ownerId").asText(),Timestamp.from(Instant.now()));
        var todo=api("GET","/projects/"+id+"/outline","owner",workspace,null,200).path("dispatchTodo");
        assertEquals("OUTLINE_CONFIGURATION_REQUIRED",todo.path("reasonCode").asText());
        assertFalse(todo.path("decisionId").asText().isBlank());
        var empty=api("GET","/projects/"+id+"/outline","owner",workspace,null,200).path("editExpectedRef");
        var outline=json.createObjectNode().put("schemaVersion","1"); var chapters=outline.putArray("chapters");
        chapters.addObject().put("id","chapter-1").putNull("parentId").put("order",0).put("title","Technical response").put("instructions","Respond with traceable evidence.")
                .putArray("mandatoryOutlineRefs");
        var chapter=(com.fasterxml.jackson.databind.node.ObjectNode)chapters.get(0); chapter.putArray("requirementRefs"); chapter.putArray("scoringRefs"); chapter.putArray("materialRefs");
        outline.putArray("unmappedItems"); outline.putArray("warnings");
        var incomplete=api("POST","/projects/"+id+"/commands","member",workspace,
                Map.of("operationId","outline-incomplete-candidate","expected",empty,"action","SAVE_OUTLINE","payload",Map.of("payload",outline)),200);
        assertEquals("CANDIDATE",incomplete.path("status").asText());
        var incompleteConfirm=api("POST","/projects/"+id+"/commands","owner",workspace,
                Map.of("operationId","outline-incomplete-confirm","expected",incomplete.path("editExpectedRef"),"action","CONFIRM_OUTLINE","payload",Map.of("outlineRef",incomplete.path("ref"))),422);
        assertTrue(incompleteConfirm.toString().contains("OUTLINE_COVERAGE_INCOMPLETE"));
        String mandatoryId="mandatory-outline-0-"+sha(json.writeValueAsString(baselinePayload.path("analyses").path("bidding-tender-profile").path("mandatoryOutline").get(0)));
        var validChapter=(com.fasterxml.jackson.databind.node.ObjectNode)outline.path("chapters").get(0);
        validChapter.withArray("mandatoryOutlineRefs").add(mandatoryId);
        validChapter.withArray("requirementRefs").add("REQ-1");
        var saved=api("POST","/projects/"+id+"/commands","member",workspace,
                Map.of("operationId","outline-valid-candidate","expected",incomplete.path("editExpectedRef"),"action","SAVE_OUTLINE","payload",Map.of("payload",outline)),200);
        var candidate=json.convertValue(saved.path("ref"),BiddingTypes.Ref.class);
        assertEquals("CANDIDATE",saved.path("status").asText());
        var scope=new BiddingTypes.Scope(workspace,project.path("ownerId").asText(),id);
        var unconfirmed=assertThrows(BiddingApiException.class,()->dependencies.validate(scope,List.of(candidate)));
        assertEquals("DEPENDENCY_NOT_CONFIRMED",unconfirmed.code());
        var confirmed=api("POST","/projects/"+id+"/commands","owner",workspace,
                Map.of("operationId","outline-confirm-candidate","expected",saved.path("editExpectedRef"),"action","CONFIRM_OUTLINE","payload",Map.of("outlineRef",candidate)),200);
        assertEquals(candidate.digest(),confirmed.path("ref").path("digest").asText());
        var newerSourceSet=new BiddingTypes.Ref("sourceSet","current",2,"source-digest-2");
        jdbc.update("INSERT INTO mate_bidding_revision(id,workspace_id,project_id,kind,object_id,version,payload_json,input_refs_json,status,digest,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                UUID.randomUUID().toString(),workspace,id,"sourceSet","current",2,"{\"sourceRefs\":[]}","[]","CONFIRMED",newerSourceSet.digest(),Timestamp.from(Instant.now()));
        jdbc.update("UPDATE mate_bidding_head SET version=2,selected_ref_json=? WHERE workspace_id=? AND project_id=? AND kind='sourceSet' AND object_id='current'",json.writeValueAsString(newerSourceSet),workspace,id);
        var scopeForRead=new BiddingTypes.Scope(workspace,project.path("ownerId").asText(),id);
        var strictRead=assertThrows(BiddingApiException.class,()->dependencies.validateForRead(scopeForRead,List.of(candidate)));
        assertEquals("DEPENDENCY_STALE",strictRead.code());
        var readback=api("GET","/projects/"+id+"/outline","owner",workspace,null,200);
        assertEquals("CONFIRMED",readback.path("confirmed").path("status").asText());
        assertEquals(candidate.digest(),readback.path("confirmed").path("ref").path("digest").asText());
    }

    @Test void firstEmployeeCandidateCanBeConfirmedWithoutCreatingAnEditableHead() throws Exception {
        var project=project(); String id=project.path("id").asText(); var baseline=seedBaseline(project,false).ref();
        var outline=json.createObjectNode().put("schemaVersion","1"); var chapter=outline.putArray("chapters").addObject();
        chapter.put("id","chapter-1").putNull("parentId").put("order",0).put("title","Technical response").put("instructions","Answer the technical requirement.");
        chapter.putArray("mandatoryOutlineRefs").add("mandatory-outline-0-"+sha(json.writeValueAsString(seedMandatoryItem())));
        chapter.putArray("requirementRefs").add("REQ-1"); chapter.putArray("scoringRefs"); chapter.putArray("materialRefs");
        outline.putArray("unmappedItems"); outline.putArray("warnings");
        var claim=new BiddingTypes.Claim(new BiddingTypes.Scope(workspace,project.path("ownerId").asText(),id),"task-1","attempt-1","token-1",1,1,Instant.now(),"agent-1",
                new BiddingTypes.SkillPin("bidding-outline-planning","1","skill-digest",Map.of()),"config-1","config-digest",List.of(baseline),json.createObjectNode());
        var candidate=outlines.accept(claim,outline);
        var empty=api("GET","/projects/"+id+"/outline","owner",workspace,null,200).path("editExpectedRef"); assertEquals(0,empty.path("version").asInt());
        var confirmed=api("POST","/projects/"+id+"/commands","owner",workspace,
                Map.of("operationId","confirm-first-employee-candidate","expected",empty,"action","CONFIRM_OUTLINE","payload",Map.of("outlineRef",candidate)),200);
        assertEquals(candidate.digest(),confirmed.path("ref").path("digest").asText());
        assertEquals(candidate,json.convertValue(api("GET","/projects/"+id+"/outline","owner",workspace,null,200).path("editExpectedRef"),BiddingTypes.Ref.class));
    }

    private BaselineFixture seedBaseline(com.fasterxml.jackson.databind.JsonNode project,boolean autoPlan) throws Exception {
        String id=project.path("id").asText(); var sourceSet=new BiddingTypes.Ref("sourceSet","current",1,"source-digest");
        jdbc.update("INSERT INTO mate_bidding_revision(id,workspace_id,project_id,kind,object_id,version,payload_json,input_refs_json,status,digest,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                UUID.randomUUID().toString(),workspace,id,"sourceSet","current",1,"{\"sourceRefs\":[]}","[]","CONFIRMED","source-digest",Timestamp.from(Instant.now()));
        jdbc.update("INSERT INTO mate_bidding_head(workspace_id,project_id,kind,object_id,version,selected_ref_json) VALUES(?,?,?,?,?,?)",workspace,id,"sourceSet","current",1,json.writeValueAsString(sourceSet));
        var payload=json.createObjectNode().put("schemaVersion","1"); var analyses=payload.putObject("analyses");
        analyses.putObject("bidding-tender-profile").putArray("mandatoryOutline").add(seedMandatoryItem());
        analyses.putObject("bidding-requirement-analysis").putArray("requirements").addObject().put("id","REQ-1").put("category","TECHNICAL");
        analyses.putObject("bidding-scoring-analysis").putArray("criteria"); analyses.putObject("bidding-elimination-analysis").putArray("items");
        var baseline=new BiddingTypes.Ref("analysisBaseline","current",1,"baseline-digest");
        jdbc.update("INSERT INTO mate_bidding_revision(id,workspace_id,project_id,kind,object_id,version,payload_json,input_refs_json,status,digest,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                UUID.randomUUID().toString(),workspace,id,"analysisBaseline","current",1,json.writeValueAsString(payload),json.writeValueAsString(List.of(sourceSet)),"CONFIRMED","baseline-digest",Timestamp.from(Instant.now()));
        jdbc.update("INSERT INTO mate_bidding_head(workspace_id,project_id,kind,object_id,version,selected_ref_json) VALUES(?,?,?,?,?,?)",workspace,id,"analysisBaseline","current",1,json.writeValueAsString(baseline));
        if(autoPlan) jdbc.update("INSERT INTO mate_bidding_decision(id,workspace_id,project_id,target_ref_json,decision,reason,actor_id,created_at) VALUES(?,?,?,?,?,?,?,?)",
                UUID.randomUUID().toString(),workspace,id,json.writeValueAsString(baseline),"CONFIRM_ANALYSIS","{\"autoPlanOutline\":true}",project.path("ownerId").asText(),Timestamp.from(Instant.now()));
        return new BaselineFixture(baseline,payload);
    }
    private com.fasterxml.jackson.databind.node.ObjectNode seedMandatoryItem(){return json.createObjectNode().put("name","mandatory section").put("value","include this");}
    private record BaselineFixture(BiddingTypes.Ref ref,com.fasterxml.jackson.databind.node.ObjectNode payload){}

    private String sha(String value) throws Exception {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
}
