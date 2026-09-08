package vip.mate.semantic;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import vip.mate.semantic.support.SemanticExtractionFixture;
import vip.mate.semantic.application.extraction.*;
import vip.mate.semantic.core.fact.*;
import static vip.mate.semantic.application.extraction.ExtractionContracts.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Synthetic corpus exercises deterministic boundaries; it does not measure model accuracy. */
class SemanticExtractionQualityIntegrationTest extends SemanticExtractionFixture {
    private static final Path FIXTURES = Path.of("../docs/validation/semantic-m5/fixtures");
    @Override protected Map<String,Object> definition() {
        try {
            return new ObjectMapper().convertValue(new ObjectMapper().readTree(FIXTURES.resolve("ontology.json").toFile()).path("definition"), new TypeReference<>() {});
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    @Test void measuredQualityFactNeedsMappingAndReviewBeforeTrustedReadback() throws Exception {
        String report = Files.readString(FIXTURES.resolve("01-quality-report.txt"));
        jdbc.update("UPDATE mate_wiki_raw_material SET original_content=?,extracted_text=? WHERE id=?", report, report, raw);
        String issue = call("POST",base+"/entities","member",workspace,Map.of("typeKey","QualityIssue","displayName","孔径超差"),200).path("id").asText();
        var quote = new Quote(44,48,"0.08");
        when(model.extract(any())).thenReturn(new ModelResult(List.of(new RawSuggestion(new ObjectMention("issue","QualityIssue","孔径超差"), PredicateRef.property("deviation"), new StatementValue.DecimalValue("0.08","mm"),null,Validity.unknown(),List.of(quote))),new Usage(10,5),"synthetic model"));
        var suggestion = generate(); String suggestionId = suggestion.path("id").asText();
        assertTrue(suggestion.path("diagnostics").isEmpty());
        assertEquals(0,call("GET",base+"/statements","viewer",workspace,null,200).path("total").asInt());
        call("POST",base+"/suggestions/"+suggestionId+"/submit","member",workspace,Map.of("expectedVersion",1,"operationId",op()),422);
        var edit = edit(1); edit.put("subjectTypeKey","QualityIssue");edit.put("subjectName","孔径超差");edit.put("subjectId",issue);
        edit.put("predicateKey","deviation");edit.put("value","0.08");edit.put("unit","mm");edit.put("quotes",List.of(Map.of("startCodePoint",44,"endCodePoint",48,"exactQuote","0.08")));
        call("PATCH",base+"/suggestions/"+suggestionId,"member",workspace,edit,200);
        var submitted=call("POST",base+"/suggestions/"+suggestionId+"/submit","member",workspace,Map.of("expectedVersion",2,"operationId",op()),200);
        assertEquals(0,call("GET",base+"/statements","viewer",workspace,null,200).path("total").asInt());
        call("POST",base+"/statements/"+submitted.path("statementId").asText()+"/review","owner",workspace,Map.of("expectedRevision",1,"action","ACCEPT","reason","人工核对测量原文与对象","operationId",op()),200);
        var trusted=call("GET",base+"/statements","viewer",workspace,null,200).path("items").get(0);
        assertEquals(submitted.path("statementId"),trusted.path("id"));
        assertEquals("UNKNOWN",trusted.path("validityKind").asText());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM mate_semantic_extraction_receipt WHERE graph_id=?",Integer.class,graph));
    }

    @Test void corpusQuotesLimitsAndUntrustedInputsKeepDeterministicBoundaries() throws Exception {
        var manifest=json.readTree(FIXTURES.resolve("manifest.json").toFile());assertEquals(10,manifest.size());
        var validator=new SuggestionValidator();
        for(var entry:manifest){
            String source=Files.readString(FIXTURES.resolve(entry.path("file").asText()));
            for(var q:entry.path("referenceQuotes")) assertTrue(validator.matches(source,new Quote(q.path("startCodePoint").asInt(),q.path("endCodePoint").asInt(),q.path("exactQuote").asText())),entry.path("file").asText());
            if(entry.path("expectedHandling").asText().equals("reject")) assertThrows(IllegalArgumentException.class,()->new SourceChunker().split(source));
            else assertFalse(new SourceChunker().split(source).isEmpty());
        }
        // A model returning no facts for irrelevant text or source instructions cannot write knowledge.
        for(String file:List.of("02-no-knowledge.txt","09-untrusted-instruction.txt")){
            String source=Files.readString(FIXTURES.resolve(file));jdbc.update("UPDATE mate_wiki_raw_material SET original_content=?,extracted_text=? WHERE id=?",source,source,raw);
            when(model.extract(any())).thenReturn(new ModelResult(List.of(),new Usage(1,0),"No supported facts"));
            String task=start();assertTrue(coordinator.runNext("corpus"));
            assertEquals("SUCCEEDED",call("GET",base+"/extraction-tasks/"+task,"member",workspace,null,200).path("status").asText());
            assertEquals(0,call("GET",base+"/extraction-tasks/"+task+"/suggestions","member",workspace,null,200).path("total").asInt());
        }
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM mate_semantic_statement WHERE graph_id=?",Integer.class,graph));
    }
}
