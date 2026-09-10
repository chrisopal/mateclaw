package vip.mate.semantic.owl;

import vip.mate.semantic.core.ontology.OntologyDocumentException;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RdfXmlInputTest {
    @Test void expandsInternalEntitiesAndRemovesDtd() {
        String result = RdfXmlInput.normalize("<!DOCTYPE x [<!ENTITY ns 'urn:test:'>]><x xmlns='&ns;'>&ns;</x>");
        assertFalse(result.contains("<!DOCTYPE"));
        assertTrue(result.contains("urn:test:"));
        assertFalse(result.contains("&ns;"));
    }
    @Test void rejectsExternalDeclarationsWithoutResolvingThem() {
        for (String xml : new String[]{
                "<!DOCTYPE x SYSTEM 'file:///nonexistent-external-dtd'><x/>",
                "<!DOCTYPE x [<!ENTITY external SYSTEM 'file:///nonexistent-secret'>]><x>&external;</x>",
                "<!DOCTYPE x [<!ENTITY external SYSTEM 'http://127.0.0.1:9/no-network'>]><x/>",
                "<!DOCTYPE x [<!ENTITY % p 'unused'>]><x/>"}) {
            assertThrows(OntologyDocumentException.class, () -> RdfXmlInput.normalize(xml));
        }
    }
    @Test void enforcesEntityExpansionBudget() {
        String xml = "<!DOCTYPE x [<!ENTITY a 'a'>]><x>" + "&a;".repeat(10001) + "</x>";
        assertThrows(OntologyDocumentException.class, () -> RdfXmlInput.normalize(xml));
    }
    @Test void leavesOrdinaryXmlUnchanged() {
        String xml = "<x>ordinary &amp; escaped</x>";
        assertEquals(xml, RdfXmlInput.normalize(xml));
    }
}
