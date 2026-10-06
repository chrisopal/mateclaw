package vip.mate.presales;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import vip.mate.auth.model.UserEntity;
import vip.mate.auth.service.ActorResolver;
import vip.mate.semantic.web.SemanticApiException;
import vip.mate.workspace.conversation.model.ConversationEntity;

@ExtendWith(MockitoExtension.class)
class PresalesTranscriptPolicyTest {
    @Mock ObjectProvider<PresalesService> projects;
    @Mock PresalesService service;
    @Mock ActorResolver actors;
    PresalesTranscriptPolicy policy;
    ConversationEntity row;
    ObjectNode project;
    ObjectNode task;

    @BeforeEach
    void setup() {
        policy = new PresalesTranscriptPolicy(projects, actors);
        row = new ConversationEntity();
        row.setConversationId("presales:1:p:run");
        row.setWorkspaceId(1L);
        row.setAgentId(7L);
        row.setUsername("9");
        var user = new UserEntity();
        user.setUsername("alice");
        when(actors.requireCurrent()).thenReturn(user);
        project = new ObjectMapper().createObjectNode();
        task = project.putArray("tasks").addObject();
        task.put("conversationId", row.getConversationId())
                .put("agentId", "7")
                .put("runId", "run")
                .put("status", "SUCCEEDED");
        task.putObject("contextSnapshot").put("actorId", "9");
        lenient().when(projects.getObject()).thenReturn(service);
        lenient().when(service.get("1", "p")).thenReturn(project);
    }

    @Test
    void completedTaskUsesCurrentProjectReadPolicyWithoutExecutionVersionConstraint() {
        assertTrue(policy.canRead(row, "alice"));
        verify(service).get("1", "p");
    }

    @Test
    void sourceRevocationCannotReturnTranscript() {
        when(service.get("1", "p"))
                .thenThrow(new SemanticApiException(403, "SOURCE_UNAVAILABLE", "revoked"));
        assertThrows(SemanticApiException.class, () -> policy.canRead(row, "alice"));
    }

    @Test
    void identityWorkspaceAndTaskLinkMustAllMatch() {
        assertFalse(policy.canRead(row, "other"));
        row.setWorkspaceId(2L);
        assertFalse(policy.canRead(row, "alice"));
        row.setWorkspaceId(1L);
        task.put("agentId", "8");
        assertFalse(policy.canRead(row, "alice"));
        task.put("agentId", "7").put("runId", "different");
        assertFalse(policy.canRead(row, "alice"));
        task.put("runId", "run");
        task.withObject("contextSnapshot").put("actorId", "10");
        assertFalse(policy.canRead(row, "alice"));
    }

    @Test
    void removedTaskOrMalformedIdentityCannotGrantAccess() {
        project.putArray("tasks");
        assertFalse(policy.canRead(row, "alice"));
        row.setConversationId("presales:1:p:");
        assertFalse(policy.canRead(row, "alice"));
    }
}
