package vip.mate.semantic.security;

import org.springframework.stereotype.Service;

import vip.mate.auth.model.UserEntity;
import vip.mate.semantic.config.SemanticProperties;
import vip.mate.semantic.web.SemanticApiException;
import vip.mate.workspace.core.repository.WorkspaceMapper;
import vip.mate.workspace.core.service.WorkspaceService;

@Service
public class SemanticAccessService {
    private final SemanticPrincipalResolver principals;
    private final WorkspaceService workspaces;
    private final WorkspaceMapper mapper;
    private final SemanticProperties properties;

    public SemanticAccessService(
            SemanticPrincipalResolver principals,
            WorkspaceService workspaces,
            WorkspaceMapper mapper,
            SemanticProperties properties) {
        this.principals = principals;
        this.workspaces = workspaces;
        this.mapper = mapper;
        this.properties = properties;
    }

    public UserEntity require(String scope, String role) {
        var user = principals.require();
        if (!properties.isEnabled())
            throw new SemanticApiException(404, "SEMANTIC_DISABLED", "Semantic module is disabled");
        long workspace;
        try {
            workspace = Long.parseLong(scope);
            if (workspace <= 0) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            throw new SemanticApiException(
                    400, "WORKSPACE_REQUIRED", "Explicit X-Workspace-Id required");
        }
        var row = mapper.selectById(workspace);
        if (row == null || (row.getDeleted() != null && row.getDeleted() != 0))
            throw new SemanticApiException(404, "NOT_FOUND", "Workspace not found");
        if (!"admin".equalsIgnoreCase(user.getRole())
                && !workspaces.hasPermission(workspace, user.getId(), role))
            throw new SemanticApiException(403, "FORBIDDEN", "Workspace role requires " + role);
        return user;
    }
}
