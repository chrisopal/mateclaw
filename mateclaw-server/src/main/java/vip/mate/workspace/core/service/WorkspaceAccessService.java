package vip.mate.workspace.core.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;
import vip.mate.workspace.core.model.WorkspaceEntity;
import vip.mate.workspace.core.model.WorkspaceMemberEntity;
import vip.mate.workspace.core.repository.WorkspaceMapper;
import vip.mate.workspace.core.repository.WorkspaceMemberMapper;

/** Current host workspace facts. Domain adapters retain owner exceptions and approval policy. */
@Service
public class WorkspaceAccessService {
    private final WorkspaceMapper workspaces;
    private final WorkspaceMemberMapper members;

    public WorkspaceAccessService(WorkspaceMapper workspaces, WorkspaceMemberMapper members) {
        this.workspaces = workspaces;
        this.members = members;
    }

    public WorkspaceEntity findActiveWorkspace(long workspaceId) {
        var row = workspaces.selectById(workspaceId);
        return row == null || (row.getDeleted() != null && row.getDeleted() != 0) ? null : row;
    }

    public WorkspaceMemberEntity findActiveMembership(long workspaceId, long userId) {
        return members.selectOne(
                new LambdaQueryWrapper<WorkspaceMemberEntity>()
                        .eq(WorkspaceMemberEntity::getWorkspaceId, workspaceId)
                        .eq(WorkspaceMemberEntity::getUserId, userId)
                        .eq(WorkspaceMemberEntity::getDeleted, 0));
    }

    public boolean hasMinimumRole(long workspaceId, long userId, String role) {
        var membership = findActiveMembership(workspaceId, userId);
        return membership != null && roleLevel(membership.getRole()) >= roleLevel(role);
    }

    /** Preserve exact persisted role spelling; capability normalization is a different contract. */
    public static int roleLevel(String role) {
        return switch (role) {
            case "owner" -> 4;
            case "admin" -> 3;
            case "member" -> 2;
            case "viewer" -> 1;
            default -> 0;
        };
    }
}
