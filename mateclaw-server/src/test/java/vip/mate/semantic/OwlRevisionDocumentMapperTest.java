package vip.mate.semantic;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import vip.mate.semantic.core.ontology.*;
import vip.mate.semantic.core.policy.BusinessPolicySet;
import vip.mate.semantic.ontology.OntologyRevisionRow;
import vip.mate.semantic.ontology.OwlRevisionDocumentMapper;
import vip.mate.semantic.web.SemanticApiException;

class OwlRevisionDocumentMapperTest {
    @Test
    void rowWithoutOwlDocumentNeverConvertsImplicitly() {
        var row = row();
        var adapter = mock(OntologyDocumentPort.class);
        var mapper = new OwlRevisionDocumentMapper(new ObjectMapper().findAndRegisterModules(), adapter);
        assertThrows(SemanticApiException.class, () -> mapper.read(row));
        verifyNoInteractions(adapter);
    }

    @Test
    void completeDocumentAndPolicyRoundTripWithoutLegacyJson() {
        var row = row();
        var adapter = mock(OntologyDocumentPort.class);
        var mapper = new OwlRevisionDocumentMapper(new ObjectMapper().findAndRegisterModules(), adapter);
        var document = OntologyDocument.fromText("o", "r", "urn:test:o", Optional.of("urn:test:v1"),
                OntologyDocumentSyntax.FUNCTIONAL, "Ontology(<urn:test:o> <urn:test:v1>)", LockedImport.digest(List.of()));
        var parsed = new ParsedOntologyDocument(document, "urn:test:o", Optional.of("urn:test:v1"), List.of(), List.of(), List.of(), List.of());
        mapper.write(row, parsed, new BusinessPolicySet("policy-v1", List.of()));
        when(adapter.parse(eq(document), eq(OntologyDocumentSyntax.FUNCTIONAL), eq(List.of()))).thenReturn(parsed);
        assertEquals(parsed, mapper.read(row));
        assertEquals("policy-v1", mapper.policy(row).version());
        verify(adapter).parse(eq(document), eq(OntologyDocumentSyntax.FUNCTIONAL), eq(List.of()));
    }

    @Test
    void tamperedStoredTextCannotPassAsThePublishedDigest() {
        var row = row();
        var adapter = mock(OntologyDocumentPort.class);
        var mapper = new OwlRevisionDocumentMapper(new ObjectMapper().findAndRegisterModules(), adapter);
        var document = OntologyDocument.fromText("o", "r", "urn:test:o", Optional.empty(),
                OntologyDocumentSyntax.FUNCTIONAL, "Ontology(<urn:test:o>)", LockedImport.digest(List.of()));
        mapper.write(row, new ParsedOntologyDocument(document, "urn:test:o", Optional.empty(), List.of(), List.of(), List.of(), List.of()), new BusinessPolicySet("v1", List.of()));
        row.setDocumentText("Ontology(<urn:changed>)");
        assertThrows(IllegalStateException.class, () -> mapper.read(row));
        verifyNoInteractions(adapter);
    }

    private static OntologyRevisionRow row() {
        var row = new OntologyRevisionRow();
        row.setId("r");
        row.setOntologyId("o");
        return row;
    }
}
