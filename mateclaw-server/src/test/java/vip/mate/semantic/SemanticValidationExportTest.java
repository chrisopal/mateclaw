package vip.mate.semantic;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import vip.mate.semantic.support.SemanticHttpFixture;
import vip.mate.semantic.query.SemanticContextService;
import vip.mate.semantic.query.SemanticContextDtos;
import vip.mate.semantic.reasoning.SemanticReasoningService;
import vip.mate.semantic.reasoning.SemanticReasoningDtos;
import vip.mate.semantic.core.reasoning.ReasoningRequest;

/** Exports only synthetic, persisted/read-back inputs; never reads production data or model secrets. */
@EnabledIfSystemProperty(named="semantic.val.export",matches="true")
class SemanticValidationExportTest extends SemanticHttpFixture {
    @Autowired SemanticContextService contexts;
    @Autowired SemanticReasoningService reasoning;
    private final Map<String,String> entityIds=new HashMap<>();
    private record Seed(String domain,String graph,String revision,String evidence,Long agent,String actor,Instant at,String namespace,JsonNode source) {}
    @Test void exportPinnedSyntheticEvaluationInputs() throws Exception {
        Path out=Path.of(System.getProperty("semantic.val.output")).toAbsolutePath();Files.createDirectories(out);
        var sources=json.readTree(getClass().getResourceAsStream("/semantic/owl/sources.json"));
        var exported=new LinkedHashMap<String,Object>();
        for(var source:sources) {
            var seed=seed(source); var request=new SemanticContextDtos.Request("",Set.of(),seed.at(),12000,null);
            var context=contexts.context(workspace,seed.actor(),seed.agent(),seed.graph(),request);
            assertFalse(context.coverage().truncated(),"Frozen fixture must fit entirely in the declared context budget");
            assertFalse(context.facts().isEmpty());assertFalse(context.evidence().isEmpty());
            var classified=reasoning.reason(workspace,seed.actor(),seed.agent(),seed.graph(),new SemanticReasoningDtos.Request(
                    ReasoningRequest.AssertionScope.TBOX_ONLY,ReasoningRequest.TaskKind.CLASSIFICATION,null,null,seed.at()));
            assertEquals("CONSISTENT",classified.status().name());
            var instance=reasoning.reason(workspace,seed.actor(),seed.agent(),seed.graph(),new SemanticReasoningDtos.Request(
                    ReasoningRequest.AssertionScope.ACCEPTED_FACTS,ReasoningRequest.TaskKind.INSTANCE_TYPES,
                    seed.namespace()+(seed.domain().equals("quality")?"CMM1":"Obs1"),null,seed.at()));
            assertEquals("CONSISTENT",instance.status().name(),instance.diagnostics().toString());
            exported.put(seed.domain(),Map.of("source",source,"context",context,"reasoning",List.of(classified,instance),
                    "fixtureType","SYNTHETIC","validityMeaning","one-second observation timestamp window, not continuous current state"));
        }
        Files.writeString(out.resolve("inputs.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(exported));
    }
    private Seed seed(JsonNode source) throws Exception {
        String domain=source.path("domain").asText(),ns="urn:mateclaw:fixture:"+domain+":";
        String document=new String(getClass().getResourceAsStream("/semantic/owl/"+domain+".ofn").readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
        String ontology=create();long dv=draft(ontology).path("draftVersion").asLong();
        var saved=call("PUT","/ontologies/"+ontology+"/draft","member",workspace,saveBody(dv,owlDocument(document)),200);
        String revision=publish(ontology,saved.path("draftVersion").asLong(),op()).path("id").asText();
        String kb=id(),raw=id();var now=LocalDateTime.now();String text=source.path("text").asText();
        jdbc.update("INSERT INTO mate_wiki_knowledge_base(id,name,status,page_count,raw_count,workspace_id,create_time,update_time,deleted) VALUES(?,?,?,0,0,?,?,?,0)",Long.valueOf(kb),"VAL synthetic "+domain,"active",Long.valueOf(workspace),now,now);
        String graph=call("PUT","/knowledge-bases/"+kb+"/binding","owner",workspace,Map.of("action","ENABLE","revisionId",revision),200).path("graphId").asText();
        jdbc.update("INSERT INTO mate_wiki_raw_material(id,kb_id,title,source_type,original_content,file_size,processing_status,create_time,update_time,deleted) VALUES(?,?,?,?,?,?,?,?,?,0)",Long.valueOf(raw),Long.valueOf(kb),source.path("id").asText(),"text",text,text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length,"completed",now,now);
        String snapshot=call("POST","/graphs/"+graph+"/imports","member",workspace,Map.of("sourceKind","WIKI_RAW","sourceRef",raw,"operationId",op()),200).path("snapshotId").asText();
        String evidence=call("POST","/graphs/"+graph+"/snapshots/"+snapshot+"/evidence","member",workspace,Map.of("operationId",op(),"startCodePoint",0,"endCodePoint",text.codePointCount(0,text.length()),"exactQuote",text),200).path("id").asText();
        String actor=jdbc.queryForObject("SELECT user_id FROM mate_workspace_member WHERE workspace_id=? AND role='member' AND deleted=0",String.class,Long.valueOf(workspace));
        var seed=new Seed(domain,graph,revision,evidence,agentWithKnowledgeBase(kb),actor,Instant.parse(source.path("observedAt").asText()),ns,source);
        Map<String,String> types=domain.equals("quality")?Map.of("M1","Measurement","CMM1","CMM","P1","MeasurementProgram","Probe1","Probe","T1","ToleranceSpecification","Env1","EnvironmentObservation"):
                Map.of("Item1","Item","A","Location","Obs1","StockObservation","R1","ReorderPolicy");
        for(var entry:types.entrySet()) {
            String entity=call("POST","/graphs/"+graph+"/entities","member",workspace,Map.of("iri",ns+entry.getKey(),"assertedTypes",List.of(ns+entry.getValue()),"displayName",entry.getKey()),200).path("id").asText();
            entityIds.put(ns+entry.getKey(),entity);
            fact(seed,entry.getKey(),"ClassAssertion(<"+ns+entry.getValue()+"> <"+ns+entry.getKey()+">)");
        }
        if(domain.equals("quality")) {
            relation(seed,"M1","measuredBy","CMM1");relation(seed,"M1","usedProgram","P1");relation(seed,"M1","evaluatedAgainst","T1");relation(seed,"M1","environmentDuring","Env1");relation(seed,"CMM1","hasProbe","Probe1");
            data(seed,"M1","observedValue","0.026","decimal","mm");data(seed,"T1","upperLimit","0.010","decimal","mm");data(seed,"P1","programVersion","2.1","string",null);data(seed,"Env1","temperature","23.5","decimal","degC");
        } else {
            relation(seed,"Obs1","forItem","Item1");relation(seed,"Obs1","atLocation","A");relation(seed,"Obs1","evaluatedAgainst","R1");relation(seed,"R1","forItem","Item1");
            data(seed,"Obs1","observedQuantity","8","decimal","piece");data(seed,"R1","reorderPoint","10","decimal","piece");
        }
        return seed;
    }
    private void relation(Seed s,String subject,String predicate,String target) throws Exception {fact(s,subject,"ObjectPropertyAssertion(<"+s.namespace()+predicate+"> <"+s.namespace()+subject+"> <"+s.namespace()+target+">)");}
    private void data(Seed s,String subject,String predicate,String value,String datatype,String unit) throws Exception {
        fact(s,subject,"DataPropertyAssertion("+(unit==null?"":"Annotation(<urn:mateclaw:semantic:unit> \""+unit+"\") ")+"<"+s.namespace()+predicate+"> <"+s.namespace()+subject+"> \""+value+"\"^^<http://www.w3.org/2001/XMLSchema#"+datatype+">)");
    }
    private void fact(Seed s,String subject,String assertion) throws Exception {
        String fact=call("POST","/graphs/"+s.graph()+"/statements","member",workspace,Map.of("operationId",op(),"subjectId",entityIds.get(s.namespace()+subject),"assertionText",assertion,"validityKind","INTERVAL","validFrom",s.at().toString(),"validTo",s.at().plusSeconds(1).toString(),"evidenceIds",List.of(s.evidence())),200).path("id").asText();
        call("POST","/graphs/"+s.graph()+"/statements/"+fact+"/review","owner",workspace,Map.of("expectedRevision",1,"action","ACCEPT","reason","Synthetic fixture source verified","operationId",op()),200);
    }
    private static String id(){return com.baomidou.mybatisplus.core.toolkit.IdWorker.getIdStr();}
    private static String op(){return UUID.randomUUID().toString();}
}
