package vip.mate.semantic.graph;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import vip.mate.semantic.graph.repository.GraphMapper;
import vip.mate.semantic.ontology.OntologyWireMapper;
import vip.mate.semantic.security.SemanticAccessService;
import vip.mate.semantic.web.SemanticApiException;

class GraphOptionalBindingContractTest {
    private final GraphMapper mapper = mock(GraphMapper.class);
    private final SemanticAccessService access = mock(SemanticAccessService.class);
    private final GraphApplicationService service =
            new GraphApplicationService(mapper, access, mock(OntologyWireMapper.class));
    private GraphOntologyRevisionRow revision;

    @BeforeEach
    void binding() {
        when(mapper.knowledgeBaseWorkspace(2L)).thenReturn(1L);
        var graph = new GraphRow();
        graph.setId("9007199254740993001");
        graph.setWorkspaceId(1L);
        graph.setKbId(2L);
        graph.setOntologyRevisionId("revision");
        graph.setEnabled(true);
        graph.setMutationVersion(4L);
        graph.setUpdatedAt(LocalDateTime.of(2026, 1, 1, 0, 0));
        when(mapper.byKnowledgeBase(1L, 2L)).thenReturn(graph);
        revision = new GraphOntologyRevisionRow();
        revision.setWorkspaceId(1L);
        revision.setVersion(3);
        revision.setRevisionState("PUBLISHED");
        revision.setModelSchema(vip.mate.semantic.core.ontology.OntologyDocument.MODEL_SCHEMA);
        when(mapper.revision("revision")).thenReturn(revision);
    }

    @Test
    void optionalReadUsesFullExistingBindingProjectionAndAuthorizationOrder() {
        var binding = service.findBinding("1", "2").orElseThrow();
        assertEquals("9007199254740993001", binding.graphId());
        assertEquals(3, binding.ontologyVersion());
        assertEquals(service.get("1", "2"), binding);
        var order = inOrder(access, mapper);
        order.verify(access).require("1", "viewer");
        order.verify(mapper).knowledgeBaseWorkspace(2L);
        order.verify(mapper).byKnowledgeBase(1L, 2L);
        order.verify(mapper).revision("revision");
    }

    @ParameterizedTest
    @ValueSource(strings = {"knowledgeBase", "workspace", "binding", "revision"})
    void onlyMissingResourcesBecomeEmptyWhileExistingGetKeeps404(String missing) {
        switch (missing) {
            case "knowledgeBase" -> when(mapper.knowledgeBaseWorkspace(2L)).thenReturn(null);
            case "workspace" -> when(mapper.knowledgeBaseWorkspace(2L)).thenReturn(9L);
            case "binding" -> when(mapper.byKnowledgeBase(1L, 2L)).thenReturn(null);
            case "revision" -> when(mapper.revision("revision")).thenReturn(null);
        }
        assertTrue(service.findBinding("1", "2").isEmpty());
        assertEquals(
                404,
                assertThrows(SemanticApiException.class, () -> service.get("1", "2")).status());
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403, 409})
    void rejectedAuthorizationIsNotConvertedToMissing(int status) {
        var failure = new SemanticApiException(status, "DENIED", "rejected");
        doThrow(failure).when(access).require("1", "viewer");
        assertSame(
                failure,
                assertThrows(SemanticApiException.class, () -> service.findBinding("1", "2")));
        verifyNoInteractions(mapper);
    }

    @Test
    void revisionConflictAndUnexpectedStorageErrorsRemainFailures() {
        revision.setModelSchema("legacy");
        var failure = assertThrows(SemanticApiException.class, () -> service.findBinding("1", "2"));
        assertEquals(409, failure.status());
        assertEquals("LEGACY_ONTOLOGY_RETIRED", failure.code());
        var storage = new IllegalStateException("unavailable");
        when(mapper.byKnowledgeBase(1L, 2L)).thenThrow(storage);
        assertSame(
                storage,
                assertThrows(IllegalStateException.class, () -> service.findBinding("1", "2")));
    }

    @Test
    void invalidInputStillFailsAfterAuthorization() {
        assertEquals(
                400,
                assertThrows(SemanticApiException.class, () -> service.findBinding("1", "bad"))
                        .status());
        verify(access).require("1", "viewer");
        verifyNoInteractions(mapper);
    }
}
