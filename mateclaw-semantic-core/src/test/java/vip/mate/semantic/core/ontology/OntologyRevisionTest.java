package vip.mate.semantic.core.ontology;

import org.junit.jupiter.api.Test;

import vip.mate.semantic.core.SemanticFixtures;
import vip.mate.semantic.core.policy.BusinessPolicySet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OntologyRevisionTest {

    @Test
    void ownsTheParsedDocumentAndVersionedBusinessPolicy() {
        var revision = SemanticFixtures.voltageOntology();

        assertEquals(OntologyDocumentSyntax.FUNCTIONAL, revision.document().document().syntax());
        assertEquals("policy-v1", revision.policy().version());
        assertEquals(2, revision.nextVersion());
    }

    @Test
    void rejectsNonPositiveVersions() {
        var revision = SemanticFixtures.voltageOntology();

        assertThrows(IllegalArgumentException.class,
                () -> new OntologyRevision(revision.revisionId(), revision.ontologyId(), 0,
                        revision.document(), new BusinessPolicySet("policy-v1", java.util.List.of())));
    }
}
