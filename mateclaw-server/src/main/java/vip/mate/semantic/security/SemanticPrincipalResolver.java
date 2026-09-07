package vip.mate.semantic.security;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.ai.chat.model.ToolContext;
import vip.mate.agent.context.ChatOrigin;

import vip.mate.auth.model.UserEntity;
import vip.mate.auth.service.AuthService;
import vip.mate.semantic.web.SemanticApiException;

@Service
public class SemanticPrincipalResolver {
    private final AuthService auth;

    public SemanticPrincipalResolver(AuthService auth) {
        this.auth = auth;
    }

    public UserEntity require() {
        var identity = SecurityContextHolder.getContext().getAuthentication();
        if (identity == null
                || !identity.isAuthenticated()
                || "anonymousUser".equals(identity.getName()))
            throw new SemanticApiException(401, "UNAUTHENTICATED", "Authentication required");
        var user = auth.findByUsername(identity.getName());
        if (user == null
                || !Boolean.TRUE.equals(user.getEnabled())
                || (user.getDeleted() != null && user.getDeleted() != 0))
            throw new SemanticApiException(401, "UNAUTHENTICATED", "Active user required");
        return user;
    }
    /** Identity is supplied by the authenticated host, never by tool JSON arguments. */
    public ToolPrincipal requireTool(ToolContext context) {
        ChatOrigin origin = ChatOrigin.from(context);
        if (origin.cronOrigin() || origin.channelId() != null || !"web".equals(origin.channelType())
                || origin.requesterUserId() == null || origin.requesterUserId() <= 0)
            throw new SemanticApiException(401, "UNAUTHENTICATED", "Authenticated web origin required");
        if (origin.workspaceId() == null || origin.workspaceId() <= 0)
            throw new SemanticApiException(400, "WORKSPACE_REQUIRED", "Explicit workspace required");
        UserEntity user = auth.findById(origin.requesterUserId());
        if (user == null || !Boolean.TRUE.equals(user.getEnabled())
                || (user.getDeleted() != null && user.getDeleted() != 0))
            throw new SemanticApiException(401, "UNAUTHENTICATED", "Active user required");
        return new ToolPrincipal(origin.workspaceId().toString(), user.getId().toString());
    }

    public record ToolPrincipal(String workspaceId, String userId) {}

}
