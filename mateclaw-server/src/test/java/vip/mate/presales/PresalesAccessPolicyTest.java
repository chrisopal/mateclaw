package vip.mate.presales;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import vip.mate.auth.model.UserEntity;
import vip.mate.auth.service.ActorResolver;
import vip.mate.auth.service.AuthService;
import vip.mate.semantic.web.SemanticApiException;
import vip.mate.workspace.core.model.WorkspaceEntity;
import vip.mate.workspace.core.model.WorkspaceMemberEntity;
import vip.mate.workspace.core.repository.WorkspaceMapper;
import vip.mate.workspace.core.repository.WorkspaceMemberMapper;
import vip.mate.workspace.core.service.WorkspaceAccessService;

class PresalesAccessPolicyTest {
    private final AuthService auth = mock(AuthService.class);
    private final WorkspaceMapper workspaces = mock(WorkspaceMapper.class);
    private final WorkspaceMemberMapper members = mock(WorkspaceMemberMapper.class);
    private final UserEntity user = new UserEntity();
    private final WorkspaceEntity workspace = new WorkspaceEntity();
    private final WorkspaceMemberEntity member = new WorkspaceMemberEntity();
    private PresalesAccess access;

    @BeforeEach
    void setup() {
        SecurityContextHolder.clearContext();
        user.setId(9L);
        user.setEnabled(true);
        user.setRole("user");
        user.setDeleted(0);
        workspace.setId(1L);
        workspace.setOwnerId(9L);
        workspace.setDeleted(0);
        member.setRole("member");
        member.setDeleted(0);
        when(auth.findById(9L)).thenReturn(user);
        when(auth.findByUsername("alice")).thenReturn(user);
        when(workspaces.selectById(1L)).thenReturn(workspace);
        when(members.selectOne(any())).thenReturn(member);
        access =
                new PresalesAccess(
                        new ActorResolver(auth), new WorkspaceAccessService(workspaces, members));
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

    private void denied(Runnable call, int status, String code, String message) {
        var e = assertThrows(SemanticApiException.class, call::run);
        assertEquals(status, e.status());
        assertEquals(code, e.code());
        assertEquals(message, e.getMessage());
    }

    @Test
    void webAuthenticationPrecedesScopeAndRejectsAnonymous() {
        denied(
                () -> access.require("invalid", "viewer"),
                401,
                "UNAUTHENTICATED",
                "Authentication required");
        SecurityContextHolder.getContext()
                .setAuthentication(
                        UsernamePasswordAuthenticationToken.authenticated(
                                "anonymousUser", "", java.util.List.of()));
        denied(
                () -> access.require("1", "viewer"),
                401,
                "UNAUTHENTICATED",
                "Authentication required");
        verify(auth, never()).findByUsername("anonymousUser");
        login();
        user.setEnabled(false);
        denied(
                () -> access.require("invalid", "viewer"),
                401,
                "UNAUTHENTICATED",
                "Active user required");
    }

    @Test
    void currentAndBoundActorsShareExactRoleMatrixWithoutOwnerBypass() {
        login();
        for (String role :
                java.util.List.of("viewer", "member", "admin", "owner", "ADMIN", "unknown")) {
            member.setRole(role);
            if (java.util.List.of("admin", "owner").contains(role)) {
                assertEquals("9", access.require("1", "admin"));
                assertDoesNotThrow(() -> access.requireActor("1", "9", "admin"));
            } else {
                denied(
                        () -> access.require("1", "admin"),
                        403,
                        "FORBIDDEN",
                        "Workspace role requires admin");
                denied(
                        () -> access.requireActor("1", "9", "admin"),
                        403,
                        "FORBIDDEN",
                        "Workspace role requires admin");
            }
        }
        when(members.selectOne(any())).thenReturn(null);
        denied(
                () -> access.require("1", "viewer"),
                403,
                "FORBIDDEN",
                "Workspace role requires viewer");
        user.setRole("ADMIN");
        assertEquals("9", access.require("1", "owner"));
        assertDoesNotThrow(() -> access.requireActor("1", "9", "owner"));
    }

    @Test
    void boundIdentityParsingAndActiveRowsAreRevalidated() {
        for (String value : java.util.List.of("0", "-1", "bad", "9223372036854775808"))
            denied(
                    () -> access.requireActor("1", value, "member"),
                    401,
                    "UNAUTHENTICATED",
                    "Valid project actor required");
        denied(
                () -> access.requireActor("bad", "9", "member"),
                401,
                "UNAUTHENTICATED",
                "Valid project actor required");
        user.setDeleted(1);
        denied(
                () -> access.requireActor("1", "9", "member"),
                401,
                "UNAUTHENTICATED",
                "Active user required");
        user.setDeleted(null);
        workspace.setDeleted(1);
        denied(
                () -> access.requireActor("1", "9", "member"),
                404,
                "NOT_FOUND",
                "Workspace not found");
        workspace.setDeleted(null);
        assertDoesNotThrow(() -> access.requireActor("1", "9", "member"));
        when(members.selectOne(any())).thenReturn(null);
        denied(
                () -> access.requireActor("1", "9", "member"),
                403,
                "FORBIDDEN",
                "Workspace role requires member");
    }

    @Test
    void ownerAssignmentRequiresActualMembershipEvenForGlobalAdmin() {
        user.setRole("admin");
        assertEquals("9", access.owner("1", "9", "fallback"));
        assertEquals("fallback", access.owner("1", " ", "fallback"));
        when(members.selectOne(any())).thenReturn(null);
        denied(
                () -> access.owner("1", "9", "fallback"),
                400,
                "INVALID_OWNER",
                "Owner must be a workspace member");
        denied(
                () -> access.owner("1", "bad", "fallback"),
                400,
                "INVALID_OWNER",
                "Owner must be a workspace member");
    }

    @Test
    void allowedOnlyConvertsForbiddenAndPropagatesOtherFailures() {
        login();
        member.setRole("viewer");
        assertFalse(access.allowed("1", "member"));
        denied(
                () -> access.allowed("bad", "member"),
                400,
                "WORKSPACE_REQUIRED",
                "Explicit workspace required");
        workspace.setDeleted(1);
        denied(() -> access.allowed("1", "member"), 404, "NOT_FOUND", "Workspace not found");
    }
}
