package vip.mate.semantic;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import vip.mate.semantic.core.ontology.*;
import vip.mate.semantic.support.SemanticHttpFixture;

class OntologyImportArtifactIntegrationTest extends SemanticHttpFixture {
    @Test void exactLockedImportBytesAreDeduplicatedAcrossWorkspaceRevisions() throws Exception {
        String text="Ontology(<urn:test:shared> Declaration(Class(<urn:test:Shared>)))";
        var artifact=LockedImport.fromText("urn:test:shared","urn:test:shared",Optional.empty(),OntologyDocumentSyntax.FUNCTIONAL,text,"test-shared");
        var input=new LinkedHashMap<>(owlDocument("Ontology(<urn:test:root> Import(<urn:test:shared>) Declaration(Class(<urn:test:Child>)) SubClassOf(<urn:test:Child> <urn:test:Shared>))"));
        input.put("imports",List.of(artifact));
        for(int i=0;i<2;i++) {
            String id=create();long version=draft(id).path("draftVersion").asLong();
            var saved=call("PUT","/ontologies/"+id+"/draft","member",workspace,saveBody(version,input),200);
            var published=publish(id,saved.path("draftVersion").asLong(),UUID.randomUUID().toString());
            var lock=published.path("document").path("source").path("imports").get(0);
            assertEquals(text,lock.path("documentText").asText());
            assertEquals("test-shared",lock.path("artifactId").asText());
        }
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM mate_semantic_import_artifact WHERE workspace_id=? AND document_digest=?",Integer.class,Long.valueOf(workspace),artifact.contentDigest()));
        assertEquals(text,jdbc.queryForObject("SELECT document_text FROM mate_semantic_import_artifact WHERE workspace_id=? AND document_digest=?",String.class,Long.valueOf(workspace),artifact.contentDigest()));
        String bad=create();long version=draft(bad).path("draftVersion").asLong();
        input.put("imports",List.of(Map.of("requestedIri","urn:test:shared","resolvedOntologyIri","urn:test:shared","syntax","FUNCTIONAL","documentText",text,"contentDigest","0".repeat(64),"artifactId","spoofed")));
        call("PUT","/ontologies/"+bad+"/draft","member",workspace,saveBody(version,input),400);
        assertEquals(version,call("GET","/ontologies/"+bad+"/draft","member",workspace,null,200).path("draftVersion").asLong());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM mate_semantic_import_artifact WHERE workspace_id=?",Integer.class,Long.valueOf(workspace)));
    }
}
