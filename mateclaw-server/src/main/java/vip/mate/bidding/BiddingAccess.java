package vip.mate.bidding;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import vip.mate.auth.model.UserEntity;
import vip.mate.auth.service.AuthService;
import vip.mate.workspace.core.model.WorkspaceEntity;
import vip.mate.workspace.core.model.WorkspaceMemberEntity;
import vip.mate.workspace.core.repository.WorkspaceMapper;
import vip.mate.workspace.core.repository.WorkspaceMemberMapper;

@Service @RequiredArgsConstructor
public class BiddingAccess {
    private final AuthService auth;
    private final WorkspaceMapper workspaces;
    private final WorkspaceMemberMapper members;
    private final BiddingProperties properties;
    private final BiddingRepository repository;

    public String require(String workspaceId,String minimumRole) {
        var authentication=SecurityContextHolder.getContext().getAuthentication();
        if(authentication==null || !authentication.isAuthenticated()) throw error(401,"UNAUTHENTICATED","Active user required");
        UserEntity user=auth.findByUsername(authentication.getName());
        validateEnabled(user);
        checkFeature();
        long ws=parse(workspaceId,"WORKSPACE_REQUIRED");
        WorkspaceEntity row=workspaces.selectById(ws);
        if(row==null || deleted(row.getDeleted())) throw error(404,"NOT_FOUND","Workspace not found");
        requireWorkspaceRole(row,user,minimumRole);
        return user.getId().toString();
    }

    public void requireActor(BiddingTypes.Scope scope,String actorId) {
        UserEntity actor=auth.findById(parse(actorId,"UNAUTHENTICATED")); validateEnabled(actor); checkFeature();
        long ws=parse(scope.workspaceId(),"WORKSPACE_REQUIRED"); WorkspaceEntity row=workspaces.selectById(ws);
        if(row==null || deleted(row.getDeleted())) throw error(404,"NOT_FOUND","Workspace not found");
        requireWorkspaceRole(row,actor,"member");
    }

    public void requireApprover(BiddingTypes.Scope scope) {
        requireActor(scope,scope.actorId());
        long workspaceId=parse(scope.workspaceId(),"WORKSPACE_REQUIRED"), actorId=parse(scope.actorId(),"UNAUTHENTICATED");
        WorkspaceEntity workspace=workspaces.selectById(workspaceId);
        if(workspace.getOwnerId()!=null && workspace.getOwnerId()==actorId) return;
        WorkspaceMemberEntity member=membership(workspaceId,actorId);
        if(member!=null && ("admin".equals(member.getRole()) || "owner".equals(member.getRole()))) return;
        if(scope.projectId()!=null) {
            var project=repository.findProject(scope.workspaceId(),scope.projectId());
            if(project!=null && actorId==parse(project.get("ownerId").asText(),"NOT_FOUND")) return;
        }
        throw error(403,"FORBIDDEN","Project owner or workspace administrator required");
    }

    public void requireOwner(String workspaceId,String ownerId) {
        long ws=parse(workspaceId,"WORKSPACE_REQUIRED"), owner=parse(ownerId,"INVALID_OWNER");
        WorkspaceEntity workspace=workspaces.selectById(ws);
        if(workspace==null || deleted(workspace.getDeleted())) throw error(404,"NOT_FOUND","Workspace not found");
        UserEntity user=auth.findById(owner);
        if(user==null || !Boolean.TRUE.equals(user.getEnabled()) || deleted(user.getDeleted()))
            throw error(400,"INVALID_OWNER","Project owner must be an enabled user");
        if(membership(ws,owner)==null) throw error(400,"INVALID_OWNER","Project owner must be an active workspace member");
    }

    private void checkFeature() { if(!properties.isEnabled()) throw error(404,"BIDDING_DISABLED","Bidding module is disabled"); }
    private void validateEnabled(UserEntity user) { if(user==null || !Boolean.TRUE.equals(user.getEnabled()) || deleted(user.getDeleted())) throw error(401,"UNAUTHENTICATED","Active user required"); }
    private void requireWorkspaceRole(WorkspaceEntity ws,UserEntity user,String required) {
        if(ws.getOwnerId()!=null && ws.getOwnerId().equals(user.getId())) return;
        if("admin".equalsIgnoreCase(user.getRole())) return;
        WorkspaceMemberEntity member=membership(ws.getId(),user.getId());
        if(member==null || rank(member.getRole())<rank(required)) throw error(403,"FORBIDDEN","Workspace role requires "+required);
    }
    private WorkspaceMemberEntity membership(long ws,long user) {
        return members.selectOne(new LambdaQueryWrapper<WorkspaceMemberEntity>().eq(WorkspaceMemberEntity::getWorkspaceId,ws)
            .eq(WorkspaceMemberEntity::getUserId,user).eq(WorkspaceMemberEntity::getDeleted,0));
    }
    private static int rank(String role) { return switch(role) { case "owner" -> 4; case "admin" -> 3; case "member" -> 2; case "viewer" -> 1; default -> 0; }; }
    private static boolean deleted(Integer value) { return value!=null && value!=0; }
    static long parse(String value,String code) { try { long n=Long.parseLong(value); if(n>0) return n; } catch(Exception ignored) {} throw error("WORKSPACE_REQUIRED".equals(code)?400:code.equals("UNAUTHENTICATED")?401:400,code,"Invalid identifier"); }
    static BiddingApiException error(int status,String code,String message) { return new BiddingApiException(status,code,message); }
}
