package vip.mate.semantic;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import vip.mate.semantic.support.SemanticHttpFixture;
import java.util.*;

/** Published OWL bytes and identities remain immutable across draft revisions. */
class SemanticOntologyM4IntegrationTest extends SemanticHttpFixture {
    @Test void publishedOwlBytesAndReplaySurviveNewDraftEdits() throws Exception {
        String id=create(); var saved=save(id,draft(id).path("draftVersion").asLong());
        long version=saved.path("draftVersion").asLong(); String operation=UUID.randomUUID().toString();
        var published=publish(id,version,operation);
        String bytes=jdbc.queryForObject("SELECT document_text FROM mate_semantic_ontology_revision WHERE id=?",String.class,published.path("id").asText());
        assertEquals(published,publish(id,version,operation));
        var copied=call("POST","/ontologies/"+id+"/draft","member",workspace,Map.of("baseRevisionId",published.path("id").asText()),200);
        var model=new LinkedHashMap<>(definition());
        model.put("documentText",bytes.stripTrailing().replaceFirst("\\)\\s*$", " Declaration(Class(<urn:test:NewEquipment>)))"));
        var changed=call("PUT","/ontologies/"+id+"/draft","member",workspace,saveBody(copied.path("draftVersion").asLong(),model),200);
        long current=changed.path("draftVersion").asLong();
        call("PUT","/ontologies/"+id+"/draft","member",workspace,saveBody(current-1,model),409);
        var v2=publish(id,current,UUID.randomUUID().toString());
        assertEquals(2,v2.path("version").asInt());
        assertNotEquals(published.path("document").path("documentDigest"),v2.path("document").path("documentDigest"));
        assertEquals(bytes,jdbc.queryForObject("SELECT document_text FROM mate_semantic_ontology_revision WHERE id=?",String.class,published.path("id").asText()));
        assertEquals(published,call("GET","/ontologies/"+id+"/revisions/"+published.path("id").asText(),"viewer",workspace,null,200));
    }
    @Test void rejectsOldFormatsAndUnknownFieldsWithoutSaving() throws Exception {
        String id=create();long version=draft(id).path("draftVersion").asLong();
        for (var forbidden : Map.of("definitionFormatVersion",2,"types",List.of(),"script","ignored?").entrySet()) {
            var input=new LinkedHashMap<>(definition());input.put(forbidden.getKey(),forbidden.getValue());
            call("PUT","/ontologies/"+id+"/draft","member",workspace,saveBody(version,input),400);
        }
        var malformed=new LinkedHashMap<>(definition());malformed.put("documentText",42);
        call("PUT","/ontologies/"+id+"/draft","member",workspace,saveBody(version,malformed),400);
        assertEquals(version,call("GET","/ontologies/"+id+"/draft","member",workspace,null,200).path("draftVersion").asLong());
    }
    @Test void sharedLabelsDoNotMergeDistinctClassIris() throws Exception {
        String id=create();long version=draft(id).path("draftVersion").asLong();
        var input=owlDocument("""
            Ontology(<urn:test:labels>
              Declaration(Class(<urn:test:Equipment>)) Declaration(Class(<urn:test:Facility>))
              AnnotationAssertion(<http://www.w3.org/2000/01/rdf-schema#label> <urn:test:Equipment> "设施"@zh)
              AnnotationAssertion(<http://www.w3.org/2000/01/rdf-schema#label> <urn:test:Facility> "设施"@zh))
            """);
        var saved=call("PUT","/ontologies/"+id+"/draft","member",workspace,saveBody(version,input),200);
        var published=publish(id,saved.path("draftVersion").asLong(),UUID.randomUUID().toString());
        assertTrue(published.path("document").path("source").path("documentText").asText().contains("<urn:test:Facility>"));
        assertTrue(published.path("document").path("source").path("documentText").asText().contains("<urn:test:Equipment>"));
        assertEquals(4,published.path("document").path("axioms").size());
    }
}
