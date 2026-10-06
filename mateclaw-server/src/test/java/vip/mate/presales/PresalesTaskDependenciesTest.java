package vip.mate.presales;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import vip.mate.agent.execution.ProjectExecutionOptions;
import vip.mate.semantic.web.SemanticApiException;
import vip.mate.workspace.core.service.ProjectSourceAccess;

class PresalesTaskDependenciesTest {
    private final ObjectMapper json = new ObjectMapper();
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final PresalesAccess access = mock(PresalesAccess.class);
    private final ProjectSourceAccess sources = mock(ProjectSourceAccess.class);
    private final PresalesContextProvider contexts =
            new PresalesContextProvider(jdbc, json, access, sources);

    private ObjectNode project() throws Exception {
        when(access.require("w", "member")).thenReturn("actor");
        when(sources.canEmployeeReadKb(anyString(), anyString(), anyString())).thenReturn(true);
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());
        return (ObjectNode)
                json.readTree(
                        """
                {"id":"p","storageVersion":2,"version":2,"agentId":"employee",
                 "name":"Case","customer":"Customer","industry":"Manufacturing","goal":"Scope",
                 "materials":[{"id":"m","version":1,"kbId":"kb","graphId":"g","role":"PRODUCT"}],
                 "requirements":[{"id":"r","version":1,"text":"Original requirement"}],
                 "clarifications":[],"baselines":[],"fitGaps":[],"cases":[],
                 "solutions":[{"id":"s","version":1,"sections":[{"title":"First","text":"Original section"}]}]}
                """);
    }

    @Test
    void snapshotCarriesExplicitDependenciesAndAllowsUnrelatedMetadataEdits() throws Exception {
        var project = project();
        var snapshot = contexts.snapshot("w", project, "S5", "Draft");
        assertEquals(2, snapshot.path("schemaVersion").asInt());
        assertTrue(snapshot.path("taskDependencies").isObject());
        assertFalse(snapshot.path("dependencyDigest").asText().isBlank());
        project.put("version", 3).put("owner", "New owner").put("updatedAt", "later");
        assertDoesNotThrow(() -> contexts.revalidate("w", project, snapshot));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "requirements",
                "clarifications",
                "baselines",
                "fitGaps",
                "cases",
                "solutions",
                "materials"
            })
    void allMemberSelectionsDetectAdditionsAndDeletions(String collection) throws Exception {
        var project = project();
        var snapshot = contexts.snapshot("w", project, "S5", "Draft");
        project.withArray(collection).addObject().put("id", "new").put("version", 1);
        assertStale(project, snapshot);
        project.withArray(collection).removeAll();
        // Empty-at-capture collections have no deletion to detect.
        if (!snapshot.path(collection).isEmpty() || "materials".equals(collection))
            assertStale(project, snapshot);
    }

    @ParameterizedTest
    @ValueSource(strings = {"name", "customer", "industry", "goal"})
    void metadataActuallySentToModelIsPinned(String field) throws Exception {
        var project = project();
        var snapshot = contexts.snapshot("w", project, "S5", "Draft");
        project.put(field, "Changed");
        assertStale(project, snapshot);
    }

    @ParameterizedTest
    @ValueSource(strings = {"requirements", "solutions", "materials"})
    void sameRevisionContentChangesCannotHideBehindRevisionNumber(String collection)
            throws Exception {
        var project = project();
        var snapshot = contexts.snapshot("w", project, "S5", "Draft");
        ((ObjectNode) project.path(collection).get(0)).put("text", "Changed");
        assertStale(project, snapshot);
    }

    @ParameterizedTest
    @ValueSource(strings = {"S6", "S7"})
    void selectedSolutionDoesNotDependOnOtherSolutionsButDoesDependOnItsSectionsAndFits(
            String skill) throws Exception {
        var project = project();
        var snapshot = contexts.snapshot("w", project, skill, "Review");
        project.put("version", 3);
        project.withArray("solutions").addObject().put("id", "other").put("version", 1);
        assertDoesNotThrow(() -> contexts.revalidate("w", project, snapshot));
        ((ObjectNode) project.path("solutions").get(0).path("sections").get(0))
                .put("text", "Changed");
        assertStale(project, snapshot);
        var changedFits = project();
        changedFits.withArray("fitGaps").addObject().put("id", "fit").put("version", 1);
        assertStale(changedFits, snapshot);
    }

    @Test
    void v2CannotDowngradeToMissingOrTamperedDependencyManifest() throws Exception {
        var project = project();
        var snapshot = contexts.snapshot("w", project, "S5", "Draft");
        var missing = snapshot.deepCopy();
        missing.remove(List.of("taskDependencies", "dependencyDigest"));
        missing.put("schemaVersion", 1);
        assertScopeChanged(project, missing);
        snapshot.put("dependencyDigest", "forged");
        assertScopeChanged(project, snapshot);
    }

    @Test
    void capturedObjectRevisionChangesAndRemovalAreRejected() throws Exception {
        var project = project();
        var snapshot = contexts.snapshot("w", project, "S6", "Presentation");
        ((ObjectNode) project.path("solutions").get(0)).put("version", 2);
        assertStale(project, snapshot);
        project.withArray("solutions").removeAll();
        assertStale(project, snapshot);
    }

    @Test
    void persistedSnapshotRoundTripKeepsExactRevisionManifest() throws Exception {
        var project = project();
        var snapshot =
                (ObjectNode)
                        json.readTree(
                                json.writeValueAsString(
                                        contexts.snapshot("w", project, "S5", "Draft")));
        project.put("version", 3).put("ownerId", "new-owner");
        assertDoesNotThrow(() -> contexts.revalidate("w", project, snapshot));
    }

    @Test
    void toolScopeAcceptsMetadataEditButRejectsManifestSubstitutionAndInputChanges()
            throws Exception {
        var project = project();
        var snapshot = contexts.snapshot("w", project, "S5", "Draft");
        var task = runningTask(snapshot);
        project.putArray("tasks").add(task);
        var service = mock(PresalesService.class);
        when(service.getForExecution("w", "p", "actor")).thenReturn(project);
        when(service.find(project, "tasks", "t")).thenReturn(task);
        var runtime = mock(PresalesEmployeeRuntime.class);
        var provider =
                new PresalesExecutionRevalidationProvider(access, service, runtime, contexts);
        var options = options(snapshot.path("dependencyDigest").asText());
        project.put("version", 3).put("ownerId", "someone");
        assertDoesNotThrow(() -> provider.requireActive(options));
        assertEquals(
                "TASK_SCOPE_CHANGED",
                assertThrows(
                                SemanticApiException.class,
                                () -> provider.requireActive(options("v2:" + "0".repeat(64))))
                        .code());
        ((ObjectNode) project.path("requirements").get(0)).put("version", 2);
        assertEquals(
                "TASK_INPUT_CHANGED",
                assertThrows(SemanticApiException.class, () -> provider.requireActive(options))
                        .code());
    }

    @Test
    void v2ToolScopeCannotUseLegacySnapshotWithoutManifest() throws Exception {
        var project = project();
        var snapshot = contexts.snapshot("w", project, "S5", "Draft");
        snapshot.remove(List.of("taskDependencies", "dependencyDigest"));
        snapshot.put("schemaVersion", 1);
        var task = runningTask(snapshot);
        var service = mock(PresalesService.class);
        when(service.getForExecution("w", "p", "actor")).thenReturn(project);
        when(service.find(project, "tasks", "t")).thenReturn(task);
        var provider =
                new PresalesExecutionRevalidationProvider(
                        access, service, mock(PresalesEmployeeRuntime.class), contexts);
        assertEquals(
                "TASK_SCOPE_CHANGED",
                assertThrows(SemanticApiException.class, () -> provider.requireActive(options("")))
                        .code());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ownerId", "requirements"})
    @SuppressWarnings("unchecked")
    void terminalPersistenceUsesLatestCasVersionWithoutDiscardingValidResult(String edit)
            throws Exception {
        var project = project();
        var snapshot = contexts.snapshot("w", project, "S5", "Draft");
        var task = runningTask(snapshot);
        project.putArray("tasks").add(task.deepCopy());
        var service = mock(PresalesService.class);
        when(service.get("w", "p")).thenAnswer(i -> project.deepCopy());
        when(service.find(any(), eq("tasks"), eq("t")))
                .thenAnswer(i -> (ObjectNode) ((ObjectNode) i.getArgument(0)).path("tasks").get(0));
        var model = mock(PresalesEmployeeRuntime.class);
        when(model.instructionsForTask(anyString(), anyString(), any(), any()))
                .thenReturn("Generate");
        when(model.execute(
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        any(),
                        any()))
                .thenAnswer(
                        i -> {
                            project.put("version", 3);
                            if (edit.equals("ownerId")) project.put("ownerId", "new-owner");
                            else
                                ((ObjectNode) project.path("requirements").get(0))
                                        .put("version", 2);
                            return json.createObjectNode().put("schemaVersion", 1);
                        });
        var coordinator =
                new PresalesGenerationCoordinator(
                        service,
                        contexts,
                        model,
                        json,
                        (ObjectProvider<PresalesPresentationHook>) mock(ObjectProvider.class));
        coordinator.process(
                new PresalesGenerationCoordinator.Submission(
                        "w", "actor", "p", "op", "S5", "Draft", task, snapshot, 2));
        verify(service)
                .saveEmployeeTask(
                        eq("w"),
                        eq("p"),
                        argThat(
                                command ->
                                        command.expectedVersion() == 3
                                                && (edit.equals("ownerId")
                                                        ? "SUCCEEDED"
                                                                .equals(
                                                                        command.payload()
                                                                                .path("status")
                                                                                .asText())
                                                        : "TASK_INPUT_CHANGED"
                                                                .equals(
                                                                        command.payload()
                                                                                .path("error")
                                                                                .asText()))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"id", "version"})
    void newSnapshotsRejectObjectsWithoutExplicitIdentityAndRevision(String missing)
            throws Exception {
        var project = project();
        ((ObjectNode) project.path("requirements").get(0)).remove(missing);
        assertEquals(
                "TASK_SCOPE_CHANGED",
                assertThrows(
                                SemanticApiException.class,
                                () -> contexts.snapshot("w", project, "S5", "Draft"))
                        .code());
    }

    private ObjectNode runningTask(ObjectNode snapshot) {
        var task =
                json.createObjectNode()
                        .put("id", "t")
                        .put("status", "RUNNING")
                        .put("runId", "run")
                        .put("operationId", "op")
                        .put("agentId", "employee")
                        .put("conversationId", "presales:w:p:run")
                        .put("skill", "S5");
        task.set("contextSnapshot", snapshot);
        return task;
    }

    private ProjectExecutionOptions options(String digest) {
        return new ProjectExecutionOptions(
                "run",
                "model",
                "config",
                "skill",
                "skill-digest",
                java.util.Map.of(),
                PresalesToolPolicy.PROJECT_VISIBLE_TOOLS,
                new PresalesToolScope(
                        "w", "actor", "p", "t", "run", "op", List.of(), "employee", 2, digest),
                0,
                false,
                false,
                12,
                PresalesToolPolicy.PROJECT_VISIBLE_TOOLS);
    }

    private void assertStale(ObjectNode project, ObjectNode snapshot) {
        assertEquals(
                "TASK_INPUT_CHANGED",
                assertThrows(
                                SemanticApiException.class,
                                () -> contexts.revalidate("w", project, snapshot))
                        .code());
    }

    private void assertScopeChanged(ObjectNode project, ObjectNode snapshot) {
        assertEquals(
                "TASK_SCOPE_CHANGED",
                assertThrows(
                                SemanticApiException.class,
                                () -> contexts.revalidate("w", project, snapshot))
                        .code());
    }

    @Test
    void archivedProjectIsRejectedBeforeAnyFurtherModelOrToolAccess() throws Exception {
        var project = project();
        var snapshot = contexts.snapshot("w", project, "S5", "Draft");
        project.put("status", "ARCHIVED");
        assertEquals(
                "PROJECT_ARCHIVED",
                assertThrows(
                                SemanticApiException.class,
                                () -> contexts.revalidate("w", project, snapshot))
                        .code());
    }
}
