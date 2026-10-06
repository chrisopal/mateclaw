package vip.mate.presales;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import vip.mate.agent.AgentService;
import vip.mate.agent.execution.ProjectToolPolicy;
import vip.mate.agent.model.AgentEntity;
import vip.mate.llm.service.ModelConfigService;
import vip.mate.semantic.web.SemanticApiException;
import vip.mate.workspace.conversation.ConversationService;

class PresalesEmployeeQueryContractTest {
    private final ObjectMapper json = new ObjectMapper();
    private final AgentService agents = mock(AgentService.class);
    private final PresalesAccess access = mock(PresalesAccess.class);
    private final ObjectProvider<AgentService> provider = provider(agents);
    private final PresalesEmployeeRuntime runtime =
            new PresalesEmployeeRuntime(
                    provider,
                    provider(mock(ConversationService.class)),
                    json,
                    mock(ModelConfigService.class),
                    mock(ProjectToolPolicy.Revalidator.class),
                    provider(mock(vip.mate.presales.repository.PresalesProjectRepository.class)));
    private final PresalesGenerationController controller =
            new PresalesGenerationController(
                    new PresalesGenerationService(
                            mock(PresalesService.class),
                            access,
                            mock(PresalesContextProvider.class),
                            runtime,
                            json,
                            mock(PresalesGenerationCoordinator.class)));

    @Test
    void httpReturnsOnlyPublicFieldsWithStringIdsInUpstreamOrder() throws Exception {
        var first = employee(9007199254740993L);
        first.setName("售前员工");
        first.setModelName("private-model");
        var second = employee(7L);
        second.setName("");
        when(agents.listAgentsByWorkspace(1L, true)).thenReturn(List.of(first, second));
        var mvc =
                MockMvcBuilders.standaloneSetup(controller)
                        .setControllerAdvice(new PresalesExceptionHandler())
                        .build();
        var response =
                mvc.perform(get("/api/v1/presales/employees").header("X-Workspace-Id", "1"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse();
        var body = json.readTree(response.getContentAsByteArray());
        assertEquals(200, body.path("code").asInt());
        assertEquals(
                json.readTree(
                        """
                        [{"id":"9007199254740993","name":"售前员工","enabled":true,"available":true},
                         {"id":"7","name":"","enabled":true,"available":true}]
                        """),
                body.path("data"));
        var order = inOrder(access, agents);
        order.verify(access).require("1", "viewer");
        order.verify(agents).listAgentsByWorkspace(1L, true);
        verifyNoMoreInteractions(agents);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "null", "disabled", "enabled-null", "deleted", "foreign-workspace",
                "workspace-null", "plan-execute", "runtime-null", "runtime-blank", "remote"
            })
    void excludesIneligibleRowsWithoutChangingEligibleOrder(String defect) throws Exception {
        AgentEntity bad = employee(99L);
        switch (defect) {
            case "null" -> bad = null;
            case "disabled" -> bad.setEnabled(false);
            case "enabled-null" -> bad.setEnabled(null);
            case "deleted" -> bad.setDeleted(1);
            case "foreign-workspace" -> bad.setWorkspaceId(2L);
            case "workspace-null" -> bad.setWorkspaceId(null);
            case "plan-execute" -> bad.setAgentType("plan_execute");
            case "runtime-null" -> bad.setRuntimeType(null);
            case "runtime-blank" -> bad.setRuntimeType("");
            case "remote" -> bad.setRuntimeType("remote");
            default -> throw new AssertionError(defect);
        }
        when(agents.listAgentsByWorkspace(1L, true))
                .thenReturn(Arrays.asList(employee(8L), bad, employee(7L)));
        assertEquals(
                json.readTree(
                        """
                        [{"id":"8","name":"employee","enabled":true,"available":true},
                         {"id":"7","name":"employee","enabled":true,"available":true}]
                        """),
                json.valueToTree(runtime.employees("1")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"native", "NATIVE", "Native"})
    void preservesCaseInsensitiveRuntimeAndLegacyNullDeletion(String type) {
        var employee = employee(7L);
        employee.setRuntimeType(type);
        employee.setDeleted(null);
        employee.setAgentType(null);
        when(agents.listAgentsByWorkspace(1L, true)).thenReturn(List.of(employee));
        assertEquals(1, runtime.employees("1").size());
    }

    @Test
    void snapshotDoesNotExposeMutableAgentState() {
        var employee = employee(7L);
        when(agents.listAgentsByWorkspace(1L, true)).thenReturn(List.of(employee));
        var result = runtime.employees("1");
        var before = json.valueToTree(result);
        employee.setId(8L);
        employee.setName("changed");
        employee.setEnabled(false);
        assertEquals(before, json.valueToTree(result));
        assertThrows(UnsupportedOperationException.class, result::clear);
    }

    @Test
    void missingRuntimeReturnsEmptyListAfterAuthorization() {
        when(provider.getIfAvailable()).thenReturn(null);
        assertEquals(List.of(), controller.employees("1").getData());
        verify(access).require("1", "viewer");
        verify(provider, never()).getObject();
        verifyNoInteractions(agents);
    }

    @Test
    void upstreamFailureIsNotConvertedToAnEmptySuccess() {
        var failure = new IllegalStateException("upstream unavailable");
        when(agents.listAgentsByWorkspace(1L, true)).thenThrow(failure);
        assertSame(
                failure,
                assertThrows(IllegalStateException.class, () -> controller.employees("1")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"id", "name"})
    void malformedEligibleIdentityStillFailsInsteadOfReturningNull(String field) {
        var employee = employee(7L);
        if (field.equals("id")) employee.setId(null);
        else employee.setName(null);
        when(agents.listAgentsByWorkspace(1L, true)).thenReturn(List.of(employee));
        assertThrows(NullPointerException.class, () -> runtime.employees("1"));
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403, 404})
    void authorizationFailurePrecedesProviderAndQuery(int statusCode) throws Exception {
        when(access.require("1", "viewer"))
                .thenThrow(new SemanticApiException(statusCode, "DENIED", "denied"));
        var mvc =
                MockMvcBuilders.standaloneSetup(controller)
                        .setControllerAdvice(new PresalesExceptionHandler())
                        .build();
        var response =
                mvc.perform(get("/api/v1/presales/employees").header("X-Workspace-Id", "1"))
                        .andExpect(status().is(statusCode))
                        .andReturn()
                        .getResponse();
        assertEquals(
                "DENIED",
                json.readTree(response.getContentAsByteArray()).path("data").path("code").asText());
        verifyNoInteractions(provider, agents);
    }

    private static AgentEntity employee(long id) {
        var entity = new AgentEntity();
        entity.setId(id);
        entity.setName("employee");
        entity.setWorkspaceId(1L);
        entity.setEnabled(true);
        entity.setDeleted(0);
        entity.setAgentType("chat");
        entity.setRuntimeType("native");
        return entity;
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        when(provider.getObject()).thenReturn(value);
        return provider;
    }
}
