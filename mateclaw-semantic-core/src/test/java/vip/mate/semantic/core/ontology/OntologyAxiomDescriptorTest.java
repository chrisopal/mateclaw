package vip.mate.semantic.core.ontology;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OntologyAxiomDescriptorTest {
    @Test void signatureOrderIsCanonicalAcrossConstructionAndReplay() {
        var original=new LinkedHashSet<>(List.of("urn:z", "urn:a", "urn:m"));
        var first=descriptor(original);
        var replay=descriptor(new LinkedHashSet<>(List.of("urn:m", "urn:z", "urn:a")));
        assertEquals(List.of("urn:a", "urn:m", "urn:z"),new ArrayList<>(first.signatureIris()));
        assertEquals(new ArrayList<>(first.signatureIris()),new ArrayList<>(replay.signatureIris()));
        original.add("urn:b");
        assertEquals(3,first.signatureIris().size());
        assertThrows(UnsupportedOperationException.class,()->first.signatureIris().add("urn:c"));
    }
    private OntologyAxiomDescriptor descriptor(java.util.Set<String> signature) {
        return new OntologyAxiomDescriptor("axiom","SubClassOf","rendering",signature,List.of(),true);
    }
}
