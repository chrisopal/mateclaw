package vip.mate.presales;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import vip.mate.auth.model.UserEntity;
import vip.mate.auth.service.AuthService;
import vip.mate.semantic.security.SemanticPrincipalResolver;
import vip.mate.semantic.web.SemanticApiException;
import vip.mate.workspace.core.model.WorkspaceEntity;
import vip.mate.workspace.core.model.WorkspaceMemberEntity;
import vip.mate.workspace.core.repository.WorkspaceMapper;
import vip.mate.workspace.core.repository.WorkspaceMemberMapper;

class PresalesAccessActorTest {
    @Test
    void parallelToolThreadsRevalidateBoundActorWithoutSecurityContext() throws Exception {
        var auth = mock(AuthService.class);
        var workspaces = mock(WorkspaceMapper.class);
        var members = mock(WorkspaceMemberMapper.class);
        var user = mock(UserEntity.class);
        var workspace = mock(WorkspaceEntity.class);
        var membership = mock(WorkspaceMemberEntity.class);
        when(user.getEnabled()).thenReturn(true);
        when(user.getRole()).thenReturn("user");
        when(membership.getRole()).thenReturn("member");
        when(auth.findById(9L)).thenReturn(user);
        when(workspaces.selectById(1L)).thenReturn(workspace);
        when(members.selectOne(org.mockito.ArgumentMatchers.any())).thenReturn(membership);
        var access =
                new PresalesAccess(
                        mock(SemanticPrincipalResolver.class), auth, workspaces, members);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> access.requireActor("1", "9", "member"));
            var second = executor.submit(() -> access.requireActor("1", "9", "member"));
            assertDoesNotThrow(
                    () -> {
                        first.get();
                    });
            assertDoesNotThrow(
                    () -> {
                        second.get();
                    });
        }

        when(user.getEnabled()).thenReturn(false);
        assertThrows(SemanticApiException.class, () -> access.requireActor("1", "9", "member"));
    }
}
