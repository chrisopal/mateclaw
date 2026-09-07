package vip.mate.semantic.security;

import org.springframework.stereotype.Service;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import vip.mate.auth.model.UserEntity;
import vip.mate.auth.service.AuthService;
import vip.mate.semantic.config.SemanticProperties;
import vip.mate.semantic.web.SemanticApiException;
import vip.mate.workspace.core.repository.WorkspaceMapper;
import vip.mate.workspace.core.model.WorkspaceMemberEntity;
import vip.mate.workspace.core.repository.WorkspaceMemberMapper;

@Service
public class SemanticAccessService {
    private final SemanticPrincipalResolver principals;
    private final WorkspaceMapper mapper;
    private final WorkspaceMemberMapper members;
    private final SemanticProperties properties;
    private final AuthService auth;

    public SemanticAccessService(
            SemanticPrincipalResolver principals,
            WorkspaceMapper mapper,
            WorkspaceMemberMapper members,
            SemanticProperties properties,
            AuthService auth) {
        this.principals = principals;
        this.mapper = mapper;
        this.members = members;
        this.properties = properties;
        this.auth = auth;
    }

    public UserEntity require(String scope, String role) {
        var user = principals.require();
        requireWorkspaceRole(scope, role, user);
        return user;
    }

    public UserEntity requireActor(String scope, String actorId, String role) {
        long userId;
        try {
            userId = Long.parseLong(actorId);
        } catch (RuntimeException e) {
            throw new SemanticApiException(401, "UNAUTHENTICATED", "Import actor is invalid");
        }
        UserEntity user = auth.findById(userId);
        requireWorkspaceRole(scope, role, user);
        return user;
    }

    private void requireWorkspaceRole(String scope, String role, UserEntity user) {
        if (user == null || !Boolean.TRUE.equals(user.getEnabled()) || (user.getDeleted() != null && user.getDeleted() != 0))
            throw new SemanticApiException(401, "UNAUTHENTICATED", "Active user required");
        if (!properties.isEnabled())
            throw new SemanticApiException(404, "SEMANTIC_DISABLED", "Semantic module is disabled");
        long workspace;
        try {
            workspace = Long.parseLong(scope);
            if (workspace <= 0) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            throw new SemanticApiException(400, "WORKSPACE_REQUIRED", "Explicit X-Workspace-Id required");
        }
        var row = mapper.selectById(workspace);
        if (row == null || (row.getDeleted() != null && row.getDeleted() != 0))
            throw new SemanticApiException(404, "NOT_FOUND", "Workspace not found");
        if (!"admin".equalsIgnoreCase(user.getRole()) && !hasCurrentPermission(workspace, user.getId(), role))
            throw new SemanticApiException(403, "FORBIDDEN", "Workspace role requires " + role);
    }

    private boolean hasCurrentPermission(long workspace, long userId, String requiredRole) {
        WorkspaceMemberEntity membership = members.selectOne(new LambdaQueryWrapper<WorkspaceMemberEntity>()
                .eq(WorkspaceMemberEntity::getWorkspaceId, workspace)
                .eq(WorkspaceMemberEntity::getUserId, userId)
                .eq(WorkspaceMemberEntity::getDeleted, 0));
        return membership != null && roleLevel(membership.getRole()) >= roleLevel(requiredRole);
    }

    private static int roleLevel(String role) {
        return switch (role) {
            case "owner" -> 4;
            case "admin" -> 3;
            case "member" -> 2;
            case "viewer" -> 1;
            default -> 0;
        };
    }
}
