package vip.mate.presales;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import vip.mate.auth.service.ActorResolver;
import vip.mate.workspace.conversation.ConversationTranscriptPolicy;
import vip.mate.workspace.conversation.model.ConversationEntity;

/**
 * Reuses project/source read authorization on every transcript read, including by global admins.
 */
@Component
@ConditionalOnProperty(name = "mateclaw.presales.enabled", havingValue = "true")
public class PresalesTranscriptPolicy implements ConversationTranscriptPolicy {
    private final ObjectProvider<PresalesService> projects;
    private final ActorResolver actors;

    public PresalesTranscriptPolicy(
            ObjectProvider<PresalesService> projects, ActorResolver actors) {
        this.projects = projects;
        this.actors = actors;
    }

    @Override
    public boolean supports(ConversationEntity conversation) {
        return conversation.getConversationId() != null
                && conversation.getConversationId().startsWith("presales:");
    }

    @Override
    public boolean canRead(ConversationEntity conversation, String username) {
        if (!actors.requireCurrent().getUsername().equals(username)) return false;
        String[] identity = conversation.getConversationId().split(":", -1);
        if (identity.length != 4
                || identity[2].isBlank()
                || identity[3].isBlank()
                || conversation.getWorkspaceId() == null
                || !identity[1].equals(conversation.getWorkspaceId().toString())) return false;
        var project = projects.getObject().get(identity[1], identity[2]);
        for (var task : project.path("tasks")) {
            if (conversation.getConversationId().equals(task.path("conversationId").asText())
                    && identity[3].equals(task.path("runId").asText())
                    && conversation.getAgentId() != null
                    && conversation.getAgentId().toString().equals(task.path("agentId").asText())
                    && conversation
                            .getUsername()
                            .equals(task.path("contextSnapshot").path("actorId").asText()))
                return true;
        }
        return false;
    }
}
