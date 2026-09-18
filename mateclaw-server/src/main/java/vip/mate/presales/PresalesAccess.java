package vip.mate.presales;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;
import vip.mate.semantic.security.SemanticPrincipalResolver;
import vip.mate.semantic.web.SemanticApiException;
import vip.mate.workspace.core.model.WorkspaceMemberEntity;
import vip.mate.workspace.core.repository.WorkspaceMapper;
import vip.mate.workspace.core.repository.WorkspaceMemberMapper;

@Service
public class PresalesAccess {
  private final SemanticPrincipalResolver principals;
  private final WorkspaceMapper workspaces;
  private final WorkspaceMemberMapper members;

  public PresalesAccess(
      SemanticPrincipalResolver principals,
      WorkspaceMapper workspaces,
      WorkspaceMemberMapper members) {
    this.principals = principals;
    this.workspaces = workspaces;
    this.members = members;
  }

  public String require(String scope, String role) {
    var user = principals.require();
    long workspace;
    try {
      workspace = Long.parseLong(scope);
      if (workspace <= 0) throw new NumberFormatException();
    } catch (RuntimeException e) {
      throw new SemanticApiException(400, "WORKSPACE_REQUIRED", "Explicit workspace required");
    }
    var w = workspaces.selectById(workspace);
    if (w == null || (w.getDeleted() != null && w.getDeleted() != 0))
      throw new SemanticApiException(404, "NOT_FOUND", "Workspace not found");
    if (!"admin".equalsIgnoreCase(user.getRole())) {
      var member =
          members.selectOne(
              new LambdaQueryWrapper<WorkspaceMemberEntity>()
                  .eq(WorkspaceMemberEntity::getWorkspaceId, workspace)
                  .eq(WorkspaceMemberEntity::getUserId, user.getId())
                  .eq(WorkspaceMemberEntity::getDeleted, 0));
      if (member == null || level(member.getRole()) < level(role))
        throw new SemanticApiException(403, "FORBIDDEN", "Workspace role requires " + role);
    }
    return user.getId().toString();
  }

  public String owner(String scope, String requested, String fallback) {
    if (requested == null || requested.isBlank()) return fallback;
    long id;
    try {
      id = Long.parseLong(requested);
    } catch (Exception e) {
      throw new SemanticApiException(400, "INVALID_OWNER", "Owner must be a workspace member");
    }
    var membership =
        members.selectOne(
            new LambdaQueryWrapper<WorkspaceMemberEntity>()
                .eq(WorkspaceMemberEntity::getWorkspaceId, Long.valueOf(scope))
                .eq(WorkspaceMemberEntity::getUserId, id)
                .eq(WorkspaceMemberEntity::getDeleted, 0));
    if (membership == null)
      throw new SemanticApiException(400, "INVALID_OWNER", "Owner must be a workspace member");
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

  private static int level(String r) {
    return switch (r) {
      case "owner" -> 4;
      case "admin" -> 3;
      case "member" -> 2;
      case "viewer" -> 1;
      default -> 0;
    };
  }
}
