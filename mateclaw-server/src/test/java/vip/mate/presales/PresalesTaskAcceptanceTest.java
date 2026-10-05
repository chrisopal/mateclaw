package vip.mate.presales;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import vip.mate.semantic.web.SemanticApiException;
import vip.mate.workspace.core.service.ProjectAuthorityFence;

class PresalesTaskAcceptanceTest {
    private final ObjectMapper json = new ObjectMapper();
    private final DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
    private final PresalesEmployeeRuntime runtime = mock(PresalesEmployeeRuntime.class);
    private final ProjectAuthorityFence fence = mock(ProjectAuthorityFence.class);
    private final PresalesTaskAcceptance acceptance =
            new PresalesTaskAcceptance(beans.getBeanProvider(PresalesEmployeeRuntime.class), fence);
    private ObjectNode project;
    private ObjectNode active;

    @BeforeEach
    void setUp() throws Exception {
        beans.registerSingleton("runtime", runtime);
        active =
                (ObjectNode)
                        json.readTree(
                                """
                                {"id":"task","status":"RUNNING","authority":"UNTRUSTED_DRAFT",
                                 "skill":"S1","agentId":"7","modelConfigId":"17","runId":"run",
                                 "extension":{"retained":true},"contextSnapshot":{"actorId":"8",
                                  "sources":[{"kbId":"3","sourceRef":"33","graphId":"g"}]}}
                                """);
        project = json.createObjectNode();
        project.putArray("tasks").add(active);
        when(fence.lockForResult(anyString(), any(), anyString(), anyString(), any()))
                .thenReturn(true);
    }

    @Test
    void ordinaryDraftNormalizesAuthorityWithoutConsultingExecutionServices() {
        var value = json.createObjectNode().put("authority", "APPROVED");
        acceptance.prepare("1", "9", project, value, false);
        assertEquals("DRAFT", value.path("status").asText());
        assertEquals("UNTRUSTED_DRAFT", value.path("authority").asText());
        verifyNoInteractions(fence, runtime);
    }

    @Test
    void successfulCandidateLocksDurableAuthorityBeforeRuntimeAndModelValidation()
            throws Exception {
        var before = project.deepCopy();
        var candidate = candidate("SUCCEEDED");
        acceptance.prepare("1", "9", project, candidate, true);
        var ordered = inOrder(fence, runtime);
        ordered.verify(fence)
                .lockForResult(
                        "1",
                        List.of("9", "8"),
                        "7",
                        "17",
                        List.of(new ProjectAuthorityFence.Source("3", "33", "g")));
        ordered.verify(runtime)
                .revalidate("1", "9", candidate, (ObjectNode) candidate.path("contextSnapshot"));
        ordered.verifyNoMoreInteractions();
        assertEquals(before, project, "Acceptance must not save or project a result");
        assertEquals("UNTRUSTED_DRAFT", candidate.path("authority").asText());
    }

    @Test
    void runtimeRevocationAfterConstructionIsObservedBeforeIdentityLookup() throws Exception {
        beans.destroySingleton("runtime");
        project.putArray("tasks");
        assertRejected(409, "EMPLOYEE_UNAVAILABLE", candidate("SUCCEEDED"));
        verifyNoInteractions(fence, runtime);
    }

    @Test
    void resultShapeIsCheckedBeforeSnapshotAndMissingRuntime() throws Exception {
        beans.destroySingleton("runtime");
        var candidate = candidate("SUCCEEDED");
        candidate.remove("contextSnapshot");
        candidate.put("result", "invalid");
        assertRejected(422, "MODEL_FORMAT", candidate);
        candidate.set("result", json.createObjectNode());
        assertRejected(409, "TASK_SCOPE_CHANGED", candidate);
        verifyNoInteractions(fence, runtime);
    }

    @Test
    void authorityLossStopsRuntimeAndModelValidation() throws Exception {
        when(fence.lockForResult(anyString(), any(), anyString(), anyString(), any()))
                .thenReturn(false);
        var candidate = candidate("SUCCEEDED");
        candidate.set("result", json.createObjectNode());
        assertRejected(409, "EXECUTION_AUTHORITY_CHANGED", candidate);
        verifyNoInteractions(runtime);
    }

    @Test
    void invalidAuthorityIdentifiersMapToScopeChange() throws Exception {
        when(fence.lockForResult(anyString(), any(), anyString(), anyString(), any()))
                .thenThrow(new IllegalArgumentException("Invalid execution authority identifier"));
        assertRejected(409, "TASK_SCOPE_CHANGED", candidate("SUCCEEDED"));
        verifyNoInteractions(runtime);
    }

    @Test
    void runtimeFailureIsPreservedAndPrecedesModelValidation() throws Exception {
        var denied = new SemanticApiException(403, "SOURCE_ACCESS_DENIED", "revoked");
        doThrow(denied).when(runtime).revalidate(anyString(), anyString(), any(), any());
        var candidate = candidate("SUCCEEDED");
        candidate.set("result", json.createObjectNode());
        assertSame(
                denied,
                assertThrows(
                        SemanticApiException.class,
                        () -> acceptance.prepare("1", "9", project, candidate, true)));
    }

    @Test
    void modelStillRejectsMalformedOutputAfterAuthorityRevalidation() throws Exception {
        var candidate = candidate("SUCCEEDED");
        candidate.withObject("result").put("needsHumanReview", false);
        assertRejected(422, "MODEL_FORMAT", candidate);
        verify(runtime)
                .revalidate("1", "9", candidate, (ObjectNode) candidate.path("contextSnapshot"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"SUCCEEDED", "FAILED"})
    void cancelledTaskCannotBeOverwrittenByLateResult(String status) throws Exception {
        var candidate = candidate(status);
        active.put("status", "CANCELLED");
        assertRejected(409, "TASK_SCOPE_CHANGED", candidate);
        verifyNoInteractions(fence, runtime);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SUCCEEDED", "FAILED"})
    void unknownExtensionChangesArePartOfRunIdentity(String status) throws Exception {
        var candidate = candidate(status);
        candidate.withObject("extension").put("retained", false);
        assertRejected(409, "TASK_SCOPE_CHANGED", candidate);
        verifyNoInteractions(fence, runtime);
    }

    @ParameterizedTest
    @ValueSource(strings = {"error", "rejectedOutput"})
    void successCannotIntroduceFailureDiagnostics(String field) throws Exception {
        var candidate = candidate("SUCCEEDED").put(field, "injected");
        assertRejected(409, "TASK_SCOPE_CHANGED", candidate);
        verifyNoInteractions(fence, runtime);
    }

    @Test
    void matchingFailureKeepsDiagnosticsWithoutRequiringRevokedExecutionAuthority()
            throws Exception {
        beans.destroySingleton("runtime");
        var candidate = candidate("FAILED");
        candidate.put("error", "revoked").put("rejectedOutput", "invalid");
        var before = project.deepCopy();
        acceptance.prepare("1", "9", project, candidate, true);
        assertEquals("revoked", candidate.path("error").asText());
        assertEquals("invalid", candidate.path("rejectedOutput").asText());
        assertEquals(before, project);
        verifyNoInteractions(fence, runtime);
    }

    @ParameterizedTest
    @ValueSource(strings = {"RUNNING", "DRAFT"})
    void employeeBoundaryRejectsNonTerminalCandidate(String status) throws Exception {
        assertRejected(409, "TASK_SCOPE_CHANGED", candidate(status));
        verifyNoInteractions(fence, runtime);
    }

    private ObjectNode candidate(String status) throws Exception {
        var candidate = active.deepCopy().put("status", status).put("finishedAt", "finished");
        candidate.set(
                "result",
                json.readTree(
                        """
                        {"schemaVersion":1,"needsHumanReview":true,"assumptions":[],"unknowns":[],"items":[]}
                        """));
        return candidate;
    }

    private void assertRejected(int status, String code, ObjectNode candidate) {
        var error =
                assertThrows(
                        SemanticApiException.class,
                        () -> acceptance.prepare("1", "9", project, candidate, true));
        assertEquals(status, error.status());
        assertEquals(code, error.code());
    }
}
