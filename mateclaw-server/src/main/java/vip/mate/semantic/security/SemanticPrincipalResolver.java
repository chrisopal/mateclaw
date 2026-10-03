package vip.mate.semantic.security;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.stereotype.Service;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.auth.model.UserEntity;
import vip.mate.auth.service.ActorResolver;
import vip.mate.semantic.web.SemanticApiException;

@Service
public class SemanticPrincipalResolver {
    private final ActorResolver actors;

    public SemanticPrincipalResolver(ActorResolver actors) {
        this.actors = actors;
    }

    public UserEntity require() {
        try {
            return actors.requireCurrent();
        } catch (ActorResolver.Denied e) {
            throw denied(e);
        }
    }

    /** Identity is supplied by the authenticated host, never by tool JSON arguments. */
    public ToolPrincipal requireTool(ToolContext context) {
        ChatOrigin origin = ChatOrigin.from(context);
        if (origin.cronOrigin()
                || origin.channelId() != null
                || !"web".equals(origin.channelType())
                || origin.requesterUserId() == null
                || origin.requesterUserId() <= 0)
            throw new SemanticApiException(
                    401, "UNAUTHENTICATED", "Authenticated web origin required");
        if (origin.workspaceId() == null || origin.workspaceId() <= 0)
            throw new SemanticApiException(
                    400, "WORKSPACE_REQUIRED", "Explicit workspace required");
        UserEntity user;
        try {
            user = actors.requireActiveId(origin.requesterUserId());
        } catch (ActorResolver.Denied e) {
            throw denied(e);
        }
        return new ToolPrincipal(
                origin.workspaceId().toString(), user.getId().toString(), origin.agentId());
    }

    private static SemanticApiException denied(ActorResolver.Denied e) {
        return new SemanticApiException(
                401,
                "UNAUTHENTICATED",
                e.reason() == ActorResolver.Reason.AUTHENTICATION_REQUIRED
                        ? "Authentication required"
                        : "Active user required");
    }

    public record ToolPrincipal(String workspaceId, String userId, Long agentId) {}
}
