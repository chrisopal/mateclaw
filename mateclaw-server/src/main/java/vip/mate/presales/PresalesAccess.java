package vip.mate.presales;

import org.springframework.stereotype.Service;
import vip.mate.auth.model.UserEntity;
import vip.mate.auth.service.ActorResolver;
import vip.mate.semantic.web.SemanticApiException;
import vip.mate.workspace.core.service.WorkspaceAccessService;

@Service
public class PresalesAccess {
    private final ActorResolver actors;
    private final WorkspaceAccessService workspaces;

    public PresalesAccess(ActorResolver actors, WorkspaceAccessService workspaces) {
        this.actors = actors;
        this.workspaces = workspaces;
    }

    /** Server-bound identity check for tool threads without an ambient web security context. */
    public void requireActor(String scope, String actorId, String role) {
        long userId;
        long workspace;
        try {
            userId = Long.parseLong(actorId);
            workspace = Long.parseLong(scope);
            if (userId <= 0 || workspace <= 0) throw new NumberFormatException();
        } catch (RuntimeException e) {
            throw new SemanticApiException(401, "UNAUTHENTICATED", "Valid project actor required");
        }
        UserEntity user;
        try {
            user = actors.requireActiveId(userId);
        } catch (ActorResolver.Denied e) {
            throw denied(e);
        }
        requireRole(workspace, userId, user, role);
    }

    public String require(String scope, String role) {
        UserEntity user;
        try {
            user = actors.requireCurrent();
        } catch (ActorResolver.Denied e) {
            throw denied(e);
        }
        long workspace;
        try {
            workspace = Long.parseLong(scope);
            if (workspace <= 0) throw new NumberFormatException();
        } catch (RuntimeException e) {
            throw new SemanticApiException(
                    400, "WORKSPACE_REQUIRED", "Explicit workspace required");
        }
        requireRole(workspace, user.getId(), user, role);
        return user.getId().toString();
    }

    private void requireRole(long workspace, long userId, UserEntity user, String role) {
        if (workspaces.findActiveWorkspace(workspace) == null)
            throw new SemanticApiException(404, "NOT_FOUND", "Workspace not found");
        if (!"admin".equalsIgnoreCase(user.getRole())
                && !workspaces.hasMinimumRole(workspace, userId, role))
            throw new SemanticApiException(403, "FORBIDDEN", "Workspace role requires " + role);
    }

    public String owner(String scope, String requested, String fallback) {
        if (requested == null || requested.isBlank()) return fallback;
        long id;
        try {
            id = Long.parseLong(requested);
        } catch (Exception e) {
            throw new SemanticApiException(
                    400, "INVALID_OWNER", "Owner must be a workspace member");
        }
        if (workspaces.findActiveMembership(Long.valueOf(scope), id) == null)
            throw new SemanticApiException(
                    400, "INVALID_OWNER", "Owner must be a workspace member");
        return requested;
    }

    public boolean allowed(String scope, String role) {
        try {
            require(scope, role);
            return true;
        } catch (SemanticApiException e) {
            if (e.status() == 403) return false;
            throw e;
        }
    }

    private static SemanticApiException denied(ActorResolver.Denied e) {
        return new SemanticApiException(
                401,
                "UNAUTHENTICATED",
                e.reason() == ActorResolver.Reason.AUTHENTICATION_REQUIRED
                        ? "Authentication required"
                        : "Active user required");
    }
}
