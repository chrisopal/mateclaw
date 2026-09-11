package vip.mate.semantic;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import vip.mate.semantic.core.ontology.OntologyDocument;
import vip.mate.semantic.core.ontology.OntologyDocumentSyntax;
import vip.mate.semantic.core.ontology.ParsedOntologyDocument;
import vip.mate.semantic.core.policy.BusinessPolicySet;
import vip.mate.semantic.ontology.BusinessPolicyService;
import vip.mate.semantic.ontology.OntologyRevisionRow;
import vip.mate.semantic.ontology.OntologyRow;
import vip.mate.semantic.ontology.OntologyWireMapper;
import vip.mate.semantic.ontology.repository.OntologyMapper;
import vip.mate.semantic.security.SemanticAccessService;
import vip.mate.semantic.statement.repository.CommandRecordMapper;
import vip.mate.semantic.web.OntologyDtos;
import vip.mate.semantic.web.OntologyDtos.CheckBusinessPolicySample;
import vip.mate.semantic.web.SemanticApiException;

class BusinessPolicyServiceTest {
    private static final String CLASS = "urn:test:Equipment";
    private static final String PROPERTY = "urn:test:voltage";
    private static final String DECIMAL = "http://www.w3.org/2001/XMLSchema#decimal";

    @Mock OntologyMapper mapper;
    @Mock CommandRecordMapper commands;
    @Mock SemanticAccessService access;
    @Mock OntologyWireMapper wire;
    private BusinessPolicyService service;
    private OntologyRow parent;
    private OntologyRevisionRow draft;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new BusinessPolicyService(mapper, commands, access, wire);
        parent = new OntologyRow();
        parent.setId("ontology");
        parent.setWorkspaceId(7L);
        parent.setDraftId("draft");
        draft = new OntologyRevisionRow();
        draft.setId("draft");
        draft.setOntologyId("ontology");
        draft.setVersion(1);
        draft.setDraftVersion(3L);
        draft.setRevisionState("DRAFT");
        when(mapper.lock("ontology", 7L)).thenReturn(parent);
        when(mapper.find("ontology", 7L)).thenReturn(parent);
        when(mapper.revision("draft", "ontology")).thenReturn(draft);
        when(commands.find(eq(7L), anyString())).thenReturn(null);
    }

    @Test
    void sampleAcceptsTimestampDatatypeFromDraftSignature() {
        String datatype = "http://www.w3.org/2001/XMLSchema#dateTimeStamp";
        when(wire.parsed(draft)).thenReturn(parsed());
        when(wire.classIris(any(ParsedOntologyDocument.class))).thenReturn(Set.of(CLASS));
        when(wire.termKinds(draft)).thenReturn(Map.of(PROPERTY, List.of("DataProperty"), datatype, List.of("Datatype")));
        when(wire.policy(draft)).thenReturn(new BusinessPolicySet("empty", List.of()));
        var result = service.checkSample("7", "ontology", new CheckBusinessPolicySample(3L, CLASS, true,
                Map.of(PROPERTY, List.of(new BusinessPolicySet.Literal("2026-09-11T00:00:00Z", datatype, null)))));
        assertTrue(result.valid());
    }

    @Test
    void sampleUsesCoreSemanticsAndDoesNotWriteGraphState() {
        BusinessPolicySet policy = new BusinessPolicySet("policy-v1", List.of(
                new BusinessPolicySet.Rule(CLASS, PROPERTY, true, "mm", Set.of("380"), true)));
        ParsedOntologyDocument parsed = parsed();
        when(wire.parsed(draft)).thenReturn(parsed);
        when(wire.classIris(parsed)).thenReturn(Set.of(CLASS));
        when(wire.termKinds(draft)).thenReturn(Map.of(PROPERTY, List.of("DataProperty")));
        when(wire.policy(draft)).thenReturn(policy);

        CheckBusinessPolicySample request = new CheckBusinessPolicySample(3L, CLASS, true,
                Map.of(PROPERTY, List.of(new BusinessPolicySet.Literal("381", DECIMAL, "cm"))));
        OntologyDtos.BusinessPolicyCheckView result = service.checkSample("7", "ontology", request);

        assertFalse(result.valid());
        assertEquals(Set.of("BUSINESS_UNIT_MISMATCH", "BUSINESS_VALUE_NOT_ALLOWED"),
                result.violations().stream().map(OntologyDtos.Violation::code).collect(java.util.stream.Collectors.toSet()));
        verify(mapper, never()).saveDraft(any(), anyLong());
        verify(mapper, never()).updateParent(any());
    }

    @Test
    void staleSampleVersionIsRejectedBeforePolicyEvaluation() {
        CheckBusinessPolicySample request = new CheckBusinessPolicySample(2L, CLASS, false, Map.of());
        SemanticApiException error = assertThrows(SemanticApiException.class,
                () -> service.checkSample("7", "ontology", request));
        assertEquals("DRAFT_CONFLICT", error.code());
        verifyNoInteractions(wire);
    }

    @Test
    void duplicatePolicyRulesAreRejectedAgainstDraftProjection() {
        ParsedOntologyDocument parsed = parsed();
        when(wire.parsed(draft)).thenReturn(parsed);
        when(wire.classIris(parsed)).thenReturn(Set.of(CLASS));
        when(wire.termKinds(draft)).thenReturn(Map.of(PROPERTY, List.of("DataProperty")));
        when(wire.encode(any())).thenReturn("payload");
        when(wire.policy(draft)).thenReturn(new BusinessPolicySet("policy-v1", List.of()));
        OntologyDtos.SaveBusinessPolicy request = new OntologyDtos.SaveBusinessPolicy(3L, "op-1", List.of(
                new BusinessPolicySet.Rule(CLASS, PROPERTY, true, null, Set.of()),
                new BusinessPolicySet.Rule(CLASS, PROPERTY, false, null, Set.of())));

        SemanticApiException error = assertThrows(SemanticApiException.class,
                () -> service.save("7", "ontology", request));
        assertEquals("POLICY_DUPLICATE_RULE", error.code());
        verify(mapper, never()).saveDraft(any(), anyLong());
    }

    @Test
    void policyIdentityDoesNotCollapseCommaContainingEnumerationValues() {
        ParsedOntologyDocument parsed = parsed();
        when(wire.parsed(draft)).thenReturn(parsed);
        when(wire.classIris(parsed)).thenReturn(Set.of(CLASS));
        when(wire.termKinds(draft)).thenReturn(Map.of(PROPERTY, List.of("DataProperty")));
        when(wire.encode(any())).thenReturn("payload");
        when(wire.policy(draft)).thenReturn(new BusinessPolicySet("policy-old", List.of(
                new BusinessPolicySet.Rule(CLASS, PROPERTY, false, null, Set.of("a,b")))));
        when(wire.document(draft)).thenReturn(new OntologyDtos.DocumentView(
                new OntologyDtos.DocumentInput("owl-document-v1", OntologyDocumentSyntax.FUNCTIONAL,
                        "Ontology(<urn:test:o>)", List.of(), new BusinessPolicySet("policy-old", List.of())),
                "urn:test:o", null, "digest", "imports", List.of()));
        when(wire.draft(draft)).thenReturn(mock(OntologyDtos.DraftView.class));
        when(mapper.saveDraft(any(), eq(3L))).thenReturn(1);
        OntologyDtos.SaveBusinessPolicy request = new OntologyDtos.SaveBusinessPolicy(3L, "op-2", List.of(
                new BusinessPolicySet.Rule(CLASS, PROPERTY, false, null, Set.of("a", "b"))));

        service.save("7", "ontology", request);

        verify(mapper).saveDraft(any(), eq(3L));
        verify(wire).store(any(), any());
    }

    private ParsedOntologyDocument parsed() {
        OntologyDocument document = OntologyDocument.fromText("ontology", "draft", "urn:test:o", Optional.empty(),
                OntologyDocumentSyntax.FUNCTIONAL, "Ontology(<urn:test:o>)", "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        return new ParsedOntologyDocument(document, "urn:test:o", Optional.empty(), List.of(), List.of(), List.of(), List.of());
    }
}
