package vip.mate.bidding;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import vip.mate.auth.model.UserEntity;
import vip.mate.auth.service.ActorResolver;
import vip.mate.auth.service.AuthService;
import vip.mate.workspace.core.model.WorkspaceEntity;
import vip.mate.workspace.core.model.WorkspaceMemberEntity;
import vip.mate.workspace.core.repository.WorkspaceMapper;
import vip.mate.workspace.core.repository.WorkspaceMemberMapper;
import vip.mate.workspace.core.service.WorkspaceAccessService;

class BiddingAccessPolicyTest {
    private final AuthService auth = mock(AuthService.class);
    private final WorkspaceMapper workspaces = mock(WorkspaceMapper.class);
    private final WorkspaceMemberMapper members = mock(WorkspaceMemberMapper.class);
    private final BiddingRepository repository = mock(BiddingRepository.class);
    private final BiddingProperties properties = new BiddingProperties();
    private final UserEntity user = new UserEntity();
    private final WorkspaceEntity workspace = new WorkspaceEntity();
    private final WorkspaceMemberEntity member = new WorkspaceMemberEntity();
    private BiddingAccess access;

    @BeforeEach
    void setup() {
        SecurityContextHolder.clearContext();
        properties.setEnabled(true);
        user.setId(9L);
        user.setEnabled(true);
        user.setRole("user");
        user.setDeleted(0);
        workspace.setId(1L);
        workspace.setOwnerId(10L);
        workspace.setDeleted(0);
        member.setRole("member");
        member.setDeleted(0);
        when(auth.findById(9L)).thenReturn(user);
        when(auth.findByUsername("alice")).thenReturn(user);
        when(workspaces.selectById(1L)).thenReturn(workspace);
        when(members.selectOne(any())).thenReturn(member);
        access =
                new BiddingAccess(
                        new ActorResolver(auth),
                        new WorkspaceAccessService(workspaces, members),
                        properties,
                        repository);
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private void login() {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        UsernamePasswordAuthenticationToken.authenticated(
                                "alice", "", java.util.List.of()));
    }

    private BiddingTypes.Scope scope() {
        return new BiddingTypes.Scope("1", "9", "7");
    }

    private void denied(Runnable call, int status, String code, String message) {
        var e = assertThrows(BiddingApiException.class, call::run);
        assertEquals(status, e.status());
        assertEquals(code, e.code());
        assertEquals(message, e.getMessage());
    }

    @Test
    void actorThenFeatureThenWorkspaceOrderRemainsStable() {
        properties.setEnabled(false);
        denied(
                () -> access.require("bad", "viewer"),
                401,
                "UNAUTHENTICATED",
                "Active user required");
        login();
        user.setEnabled(false);
        denied(
                () -> access.require("bad", "viewer"),
                401,
                "UNAUTHENTICATED",
                "Active user required");
        user.setEnabled(true);
        denied(
                () -> access.require("bad", "viewer"),
                404,
                "BIDDING_DISABLED",
                "Bidding module is disabled");
        properties.setEnabled(true);
        denied(
                () -> access.require("bad", "viewer"),
                400,
                "WORKSPACE_REQUIRED",
                "Invalid identifier");
        denied(
                () -> access.requireActor(scope(), "bad"),
                401,
                "UNAUTHENTICATED",
                "Invalid identifier");
    }

    @Test
    void workspaceOwnerBypassIsPreservedButProjectOwnerDoesNotBypassMembership() {
        login();
        when(members.selectOne(any())).thenReturn(null);
        denied(
                () -> access.require("1", "viewer"),
                403,
                "FORBIDDEN",
                "Workspace role requires viewer");
        workspace.setOwnerId(9L);
        assertEquals("9", access.require("1", "owner"));
        assertDoesNotThrow(() -> access.requireApprover(scope()));
        workspace.setOwnerId(10L);
        user.setRole("ADMIN");
        assertEquals("9", access.require("1", "owner"));
        assertDoesNotThrow(() -> access.requireApprover(scope()));
    }

    @Test
    void projectOwnerApprovalRemainsSeparateFromWorkspaceRole() {
        var project =
                new ObjectMapper()
                        .createObjectNode()
                        .put("id", "7")
                        .put("workspaceId", "1")
                        .put("ownerId", "9");
        when(repository.findProject("1", "7")).thenReturn(project);
        for (String role : java.util.List.of("viewer", "member", "admin", "owner")) {
            member.setRole(role);
            assertEquals(!role.equals("viewer"), access.canApproveProject(scope(), project));
            if (role.equals("viewer"))
                denied(
                        () -> access.requireApprover(scope()),
                        403,
                        "FORBIDDEN",
                        "Workspace role requires member");
            else assertDoesNotThrow(() -> access.requireApprover(scope()));
        }
        member.setRole("member");
        project.put("ownerId", "10");
        assertFalse(access.canApproveProject(scope(), project));
        denied(
                () -> access.requireApprover(scope()),
                403,
                "FORBIDDEN",
                "Project owner or workspace administrator required");
        project.put("workspaceId", "2");
        denied(
                () -> access.canApproveProject(scope(), project),
                404,
                "NOT_FOUND",
                "Project not found");
    }

    @Test
    void approverRechecksDisabledActorEvenForWorkspaceOwner() {
        workspace.setOwnerId(9L);
        var disabled = new UserEntity();
        disabled.setId(9L);
        disabled.setEnabled(false);
        disabled.setRole("admin");
        when(auth.findById(9L)).thenReturn(user, disabled);
        denied(
                () -> access.requireApprover(scope()),
                401,
                "UNAUTHENTICATED",
                "Active user required");
    }

    @Test
    void approverRechecksDeletedWorkspaceBeforeOwnerBypass() {
        workspace.setOwnerId(9L);
        when(workspaces.selectById(1L)).thenReturn(workspace, null);
        denied(() -> access.requireApprover(scope()), 404, "NOT_FOUND", "Workspace not found");
    }

    @Test
    void anonymousSentinelNeverResolvesAnEnabledDatabaseAccount() {
        when(auth.findByUsername("anonymousUser")).thenReturn(user);
        SecurityContextHolder.getContext()
                .setAuthentication(
                        UsernamePasswordAuthenticationToken.authenticated(
                                "anonymousUser", "", java.util.List.of()));
        denied(() -> access.require("1", "viewer"), 401, "UNAUTHENTICATED", "Active user required");
        verify(auth, never()).findByUsername("anonymousUser");
    }

    @Test
    void ownerMustBeEnabledAndMemberAndMembershipIsReread() {
        assertDoesNotThrow(() -> access.requireOwner("1", "9"));
        user.setDeleted(1);
        denied(
                () -> access.requireOwner("1", "9"),
                400,
                "INVALID_OWNER",
                "Project owner must be an enabled user");
        user.setDeleted(null);
        when(members.selectOne(any())).thenReturn(null);
        denied(
                () -> access.requireOwner("1", "9"),
                400,
                "INVALID_OWNER",
                "Project owner must be an active workspace member");
        workspace.setDeleted(1);
        denied(() -> access.requireOwner("1", "9"), 404, "NOT_FOUND", "Workspace not found");
    }
}
