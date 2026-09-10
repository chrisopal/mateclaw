package vip.mate.semantic;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import vip.mate.semantic.support.SemanticHttpFixture;
import static org.junit.jupiter.api.Assertions.*;

class OntologyGlobalRestrictionPublishTest extends SemanticHttpFixture {
    @ParameterizedTest
    @ValueSource(strings={
        "TransitiveObjectProperty(:p) FunctionalObjectProperty(:p)",
        "TransitiveObjectProperty(:p) InverseFunctionalObjectProperty(:p)",
        "TransitiveObjectProperty(:p) AsymmetricObjectProperty(:p)",
        "TransitiveObjectProperty(:p) IrreflexiveObjectProperty(:p)",
        "TransitiveObjectProperty(:p) DisjointObjectProperties(:p :q)",
        "TransitiveObjectProperty(:p) SubClassOf(:A ObjectHasSelf(:p))",
        "Declaration(DataProperty(:p))",
        "DatatypeDefinition(:D :D)",
        "Declaration(ObjectProperty(:r)) SubObjectPropertyOf(ObjectPropertyChain(:p :q) :r) SubObjectPropertyOf(ObjectPropertyChain(:r :p) :q)"
    })
    void rejectsPublicationWhilePreservingInvalidDraftAndPublishedHistory(String fragment) throws Exception {
        String id=create();draft(id);save(id,1);
        var original=publish(id,2,UUID.randomUUID().toString());
        var next=call("POST","/ontologies/"+id+"/draft","member",workspace,
            Map.of("baseRevisionId",original.path("id").asText()),200);
        String text="Prefix(:=<urn:test:>) Ontology(<urn:test:invalid> Declaration(Class(:A)) "
            +"Declaration(ObjectProperty(:p)) Declaration(ObjectProperty(:q)) Declaration(Datatype(:D)) "+fragment+")";
        var saved=call("PUT","/ontologies/"+id+"/draft","member",workspace,
            saveBody(next.path("draftVersion").asLong(),owlDocument(text)),200);
        var before=call("GET","/ontologies/"+id+"/draft","viewer",workspace,null,200);
        var history=call("GET","/ontologies/"+id+"/revisions","viewer",workspace,null,200);
        call("POST","/ontologies/"+id+"/draft/publish","owner",workspace,
            publishBody(saved.path("draftVersion").asLong(),UUID.randomUUID().toString()),422);
        assertEquals(before,call("GET","/ontologies/"+id+"/draft","viewer",workspace,null,200));
        assertEquals(history,call("GET","/ontologies/"+id+"/revisions","viewer",workspace,null,200));
        assertEquals(original,call("GET","/ontologies/"+id+"/revisions/"+original.path("id").asText(),"viewer",workspace,null,200));
    }
}
