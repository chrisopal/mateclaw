package vip.mate.semantic.core.ontology;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class OntologyDocumentTest {
    @Test
    void computesAndChecksExactUtf8Digest() {
        String text = "Ontology(<urn:test:文档>)";
        OntologyDocument document = OntologyDocument.fromText(
                "o-1", "r-1", "urn:test:文档", Optional.empty(),
                OntologyDocumentSyntax.FUNCTIONAL, text, "lock-digest");

        assertEquals(OntologyDocument.sha256(text), document.documentDigest());
        assertThrows(IllegalArgumentException.class, () -> new OntologyDocument(
                "o-1", "r-1", "urn:test:文档", Optional.empty(), OntologyDocumentSyntax.FUNCTIONAL,
                text, "0".repeat(64), "lock-digest", OntologyDocument.MODEL_SCHEMA));
    }

    @Test
    void validatesLockedImportDigest() {
        assertThrows(IllegalArgumentException.class, () -> new LockedImport(
                "urn:test:import", "urn:test:import", Optional.empty(), OntologyDocumentSyntax.FUNCTIONAL,
                "Ontology(<urn:test:import>)", "0".repeat(64), "artifact-1"));
    }
}
