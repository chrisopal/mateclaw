package vip.mate.semantic;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import vip.mate.semantic.support.SemanticHttpFixture;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SemanticChangeConflictIntegrationTest extends SemanticHttpFixture {
    @Test
    void approvedChangePreservesOneLiveConflictWithTheThirdCandidate() throws Exception {
        Fixture f=fixture(); JsonNode target=propose(f,"220"); review(f,target,"ACCEPT",200);
        JsonNode change=change(f,target,"380"), b=propose(f,"400"), c=propose(f,"440");
        JsonNode first=pair(f,"CHANGE_PROPOSAL",change.path("id").asText(),"STATEMENT",b.path("id").asText());
        call("POST",f.base+"/conflicts/"+first.path("id").asText()+"/resolve","owner",workspace,
                Map.of("winnerStatementId",change.path("id").asText(),"expectedMembers",List.of(first.path("left"),first.path("right")),"reason","approve replacement","operationId",op()),200);
        List<JsonNode> open=java.util.stream.StreamSupport.stream(call("GET",f.base+"/conflicts","owner",workspace,null,200).path("items").spliterator(),false).filter(row->"OPEN".equals(row.path("status").asText())).toList();
        assertEquals(1,open.size());
        JsonNode remaining=open.getFirst();
        assertEquals(Set.of(target.path("id").asText(),c.path("id").asText()),Set.of(remaining.path("left").path("statementId").asText(),remaining.path("right").path("statementId").asText()));
        assertEquals("STATEMENT",remaining.path("left").path("kind").asText());
        assertEquals("STATEMENT",remaining.path("right").path("kind").asText());
        call("POST",f.base+"/conflicts/"+remaining.path("id").asText()+"/resolve","owner",workspace,
                Map.of("winnerStatementId",c.path("id").asText(),"expectedMembers",List.of(remaining.path("left"),remaining.path("right")),"reason","third candidate confirmed","operationId",op()),200);
        JsonNode facts=call("GET",f.base+"/statements?view=trusted","viewer",workspace,null,200).path("items");
        assertEquals(1,facts.size()); assertEquals("440",facts.get(0).path("value").asText());
    }

    @Test
    void preKindConflictCommandsReplayWithoutMutatingAgain() throws Exception {
        Fixture f=fixture(); JsonNode a=propose(f,"380"),b=propose(f,"400");
        JsonNode conflict=pair(f,"STATEMENT",a.path("id").asText(),"STATEMENT",b.path("id").asText());
        String operation=op();
        var request=json.createObjectNode().put("winnerStatementId",a.path("id").asText());
        var members=request.putArray("expectedMembers");
        for(String side:List.of("left","right"))members.addObject().put("statementId",conflict.path(side).path("statementId").asText()).put("revision",1);
        request.put("reason","legacy approval").put("operationId",operation);
        JsonNode resolved=call("POST",f.base+"/conflicts/"+conflict.path("id").asText()+"/resolve","owner",workspace,request,200);
        var oldResult=resolved.deepCopy(); ((com.fasterxml.jackson.databind.node.ObjectNode)oldResult.path("left")).remove("kind"); ((com.fasterxml.jackson.databind.node.ObjectNode)oldResult.path("right")).remove("kind");
        String hash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(request)));
        jdbc.update("UPDATE mate_semantic_mutation_command SET payload_hash=?,result_json=? WHERE graph_id=? AND operation_id=?",hash,json.writeValueAsString(oldResult),f.graph,operation);
        JsonNode replay=call("POST",f.base+"/conflicts/"+conflict.path("id").asText()+"/resolve","owner",workspace,request,200);
        assertEquals("RESOLVED",replay.path("status").asText());
        assertEquals("STATEMENT",replay.path("left").path("kind").asText());
        assertEquals(2,jdbc.queryForObject("SELECT current_revision FROM mate_semantic_statement WHERE id=?",Integer.class,a.path("id").asText()));
        call("POST",f.base+"/statements/"+a.path("id").asText()+"/review","owner",workspace,Map.of("expectedRevision",2,"action","RETRACT","reason","wrong command kind","operationId",operation),409);
    }

    @Test
    void changeProposalWinsAConflictAndUpdatesItsTarget() throws Exception {
        Fixture f=fixture();JsonNode target=propose(f,"220");review(f,target,"ACCEPT",200);
        JsonNode competing=propose(f,"400");JsonNode change=change(f,target,"380");JsonNode conflict=pair(f,"CHANGE_PROPOSAL",change.path("id").asText(),"STATEMENT",competing.path("id").asText());
        reviewChange(f,change,2,"ACCEPT",409);
        String operation=op();
        call("POST",f.base+"/conflicts/"+conflict.path("id").asText()+"/resolve","owner",workspace,
                Map.of("winnerStatementId",change.path("id").asText(),"expectedMembers",List.of(conflict.path("left"),conflict.path("right")),"reason","verified change","operationId",operation),200);
        assertEquals("APPROVED",jdbc.queryForObject("SELECT status FROM mate_semantic_change_proposal WHERE id=?",String.class,change.path("id").asText()));
        assertEquals("REJECTED",jdbc.queryForObject("SELECT review_status FROM mate_semantic_statement_revision WHERE statement_id=? AND revision=2",String.class,competing.path("id").asText()));
        String content=jdbc.queryForObject("SELECT r.content_json FROM mate_semantic_statement s JOIN mate_semantic_statement_revision r ON r.statement_id=s.id AND r.revision=s.current_revision WHERE s.id=?",String.class,target.path("id").asText());
        assertEquals("380",json.readTree(content).path("value").asText());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM mate_semantic_governance_event WHERE graph_id=? AND operation_id=?",Integer.class,f.graph,operation));
    }

    @Test
    void ordinaryCandidateConflictsWithAPendingChangeProposal() throws Exception {
        Fixture f=fixture();JsonNode target=propose(f,"220");review(f,target,"ACCEPT",200);JsonNode change=change(f,target,"380"),candidate=propose(f,"400");
        pair(f,"STATEMENT",candidate.path("id").asText(),"CHANGE_PROPOSAL",change.path("id").asText());
        review(f,candidate,"ACCEPT",409);
    }

    private Fixture fixture() throws Exception {
        String ontology=create();JsonNode saved=save(ontology,draft(ontology).path("draftVersion").asLong());String revision=publish(ontology,saved.path("draftVersion").asLong(),op()).path("id").asText();
        String kb=id(),raw=id();LocalDateTime now=LocalDateTime.now();
        jdbc.update("INSERT INTO mate_wiki_knowledge_base(id,name,status,page_count,raw_count,workspace_id,create_time,update_time,deleted) VALUES(?,?,?,0,0,?,?,?,0)",Long.valueOf(kb),"typed conflicts","active",Long.valueOf(workspace),now,now);
        String graph=call("PUT","/knowledge-bases/"+kb+"/binding","owner",workspace,Map.of("action","ENABLE","revisionId",revision),200).path("graphId").asText(),base="/graphs/"+graph;
        String entity=call("POST",base+"/entities","member",workspace,Map.of("typeKey","Equipment","displayName","P-101"),200).path("id").asText();String text="Values 220V 380V 400V";
        jdbc.update("INSERT INTO mate_wiki_raw_material(id,kb_id,title,source_type,original_content,file_size,processing_status,create_time,update_time,deleted) VALUES(?,?,?,?,?,?,?,?,?,0)",Long.valueOf(raw),Long.valueOf(kb),"typed conflict source","text",text,text.length(),"completed",now,now);
        String snapshot=call("POST",base+"/imports","member",workspace,Map.of("sourceKind","WIKI_RAW","sourceRef",raw,"operationId",op()),200).path("snapshotId").asText();
        String evidence=call("POST",base+"/snapshots/"+snapshot+"/evidence","member",workspace,Map.of("operationId",op(),"startCodePoint",0,"endCodePoint",text.length(),"exactQuote",text),200).path("id").asText();return new Fixture(graph,base,entity,evidence);
    }
    private Map<String,Object> content(Fixture f,String value){return Map.of("operationId",op(),"subjectId",f.entity,"predicateKind","PROPERTY","predicateKey","voltage","valueType","DECIMAL","value",value,"unit","V","validityKind","INTERVAL","evidenceIds",List.of(f.evidence));}
    private JsonNode propose(Fixture f,String value)throws Exception{return call("POST",f.base+"/statements","member",workspace,content(f,value),200);}
    private JsonNode change(Fixture f,JsonNode target,String value)throws Exception{return call("POST",f.base+"/statements/"+target.path("id").asText()+"/changes","member",workspace,Map.of("expectedRevision",2,"operationId",op(),"content",content(f,value)),200);}
    private void review(Fixture f,JsonNode statement,String action,int status)throws Exception{int revision=jdbc.queryForObject("SELECT current_revision FROM mate_semantic_statement WHERE id=?",Integer.class,statement.path("id").asText());call("POST",f.base+"/statements/"+statement.path("id").asText()+"/review","owner",workspace,Map.of("expectedRevision",revision,"action",action,"reason","verified","operationId",op()),status);}
    private void reviewChange(Fixture f,JsonNode change,int revision,String action,int status)throws Exception{call("POST",f.base+"/changes/"+change.path("id").asText()+"/review","owner",workspace,Map.of("expectedRevision",revision,"action",action,"reason","verified","operationId",op()),status);}
    private JsonNode pair(Fixture f,String firstKind,String firstId,String secondKind,String secondId)throws Exception{return java.util.stream.StreamSupport.stream(call("GET",f.base+"/conflicts","owner",workspace,null,200).path("items").spliterator(),false).filter(item->Set.of(item.path("left").path("kind").asText()+":"+item.path("left").path("statementId").asText(),item.path("right").path("kind").asText()+":"+item.path("right").path("statementId").asText()).equals(Set.of(firstKind+":"+firstId,secondKind+":"+secondId))).findFirst().orElseThrow();}
    private record Fixture(String graph,String base,String entity,String evidence){}
    private static String op(){return UUID.randomUUID().toString();}
    private static String id(){return com.baomidou.mybatisplus.core.toolkit.IdWorker.getIdStr();}
}
