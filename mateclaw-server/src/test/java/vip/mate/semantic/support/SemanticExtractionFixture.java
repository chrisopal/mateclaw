package vip.mate.semantic.support;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.TestPropertySource;
import vip.mate.semantic.extraction.*;
import vip.mate.semantic.application.extraction.*;
import vip.mate.semantic.core.fact.*;
import static vip.mate.semantic.application.extraction.ExtractionContracts.*;
import static org.mockito.Mockito.*;

@TestPropertySource(properties="mateclaw.semantic.extraction.scheduler-enabled=false")
public abstract class SemanticExtractionFixture extends SemanticHttpFixture {
    @MockitoBean protected MateClawModelAdapter model;
    @Autowired protected ExtractionConfiguration.Feature feature;
    @Autowired protected ExtractionCoordinator coordinator;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean protected JdbcExtractionRepository extraction;
    protected String graph,base,raw,subject,kb;
    protected final String text="机床😀额定电压220V。";
    protected static final String SUBJECT_IRI="urn:mateclaw:extraction:subject";
    protected static final String EQUIPMENT_IRI="urn:test:Equipment";
    protected static final String VOLTAGE_IRI="urn:test:voltage";
    protected static final String DECIMAL_IRI="http://www.w3.org/2001/XMLSchema#decimal";
    protected static final String assertion(String subjectIri) {
        return "DataPropertyAssertion(<"+VOLTAGE_IRI+"> <"+subjectIri+"> \"220\"^^<"+DECIMAL_IRI+">)";
    }
    @BeforeEach void extractionFixture()throws Exception{
        feature.setEnabled(true);extraction.cancelActive();
        when(model.configuration("8")).thenReturn(new ModelConfiguration("8","fixture","fixed-config",Map.of()));
        when(model.models()).thenReturn(List.of(new ExtractionDtos.ModelView("8","fixture")));
        when(model.extract(any())).thenReturn(new ModelResult(List.of(new RawSuggestion(new ObjectMention(SUBJECT_IRI,Set.of(EQUIPMENT_IRI),"机床"),new vip.mate.semantic.owl.OwlAssertionAdapter().parse(assertion(SUBJECT_IRI)),null,Validity.unknown(),List.of(new Quote(0,text.codePointCount(0,text.length()),text)))),new Usage(10,5),"fixture"));
        String ontology=create();var draft=save(ontology,draft(ontology).path("draftVersion").asLong());var published=publish(ontology,draft.path("draftVersion").asLong(),op());
        kb=id();var now=LocalDateTime.now();
        jdbc.update("INSERT INTO mate_wiki_knowledge_base(id,name,status,page_count,raw_count,workspace_id,create_time,update_time,deleted) VALUES(?,?,?,0,0,?,?,?,0)",kb,"Extraction fixture","active",workspace,now,now);
        graph=call("PUT","/knowledge-bases/"+kb+"/binding","owner",workspace,Map.of("action","ENABLE","revisionId",published.path("id").asText()),200).path("graphId").asText();base="/graphs/"+graph;
        subject=call("POST",base+"/entities","member",workspace,Map.of("iri","urn:test:machine","assertedTypes",Set.of(EQUIPMENT_IRI),"displayName","机床"),200).path("id").asText();raw=id();
        jdbc.update("INSERT INTO mate_wiki_raw_material(id,kb_id,title,source_type,original_content,extracted_text,processing_status,create_time,update_time,deleted) VALUES(?,?,?,?,?,?,?,?,?,0)",raw,kb,"Fixture","text",text,text,"processed",now,now);
    }
    protected String start()throws Exception{return call("POST",base+"/extraction-tasks","member",workspace,Map.of("sourceRef",raw,"modelConfigId","8","operationId",op()),202).path("taskId").asText();}
    protected com.fasterxml.jackson.databind.JsonNode generate()throws Exception{String task=start();org.junit.jupiter.api.Assertions.assertTrue(coordinator.runNext("test"));org.junit.jupiter.api.Assertions.assertEquals("SUCCEEDED",call("GET",base+"/extraction-tasks/"+task,"member",workspace,null,200).path("status").asText(),extraction.metadata(task).toString());return call("GET",base+"/extraction-tasks/"+task+"/suggestions","member",workspace,null,200).path("items").get(0);}
    protected Map<String,Object> edit(long version){Map<String,Object> m=new LinkedHashMap<>();m.put("expectedVersion",version);m.put("operationId",op());m.put("status","OPEN");m.put("subjectIri",SUBJECT_IRI);m.put("subjectTypeIris",Set.of(EQUIPMENT_IRI));m.put("subjectName","机床");m.put("subjectId",subject);m.put("assertionText",assertion(SUBJECT_IRI));m.put("validityKind","UNKNOWN");m.put("quotes",List.of(Map.of("startCodePoint",0,"endCodePoint",text.codePointCount(0,text.length()),"exactQuote",text)));return m;}
    protected static String id(){return com.baomidou.mybatisplus.core.toolkit.IdWorker.getIdStr();}
    protected static String op(){return UUID.randomUUID().toString();}
}
