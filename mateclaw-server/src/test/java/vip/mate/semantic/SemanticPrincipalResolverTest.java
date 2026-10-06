package vip.mate.semantic;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.auth.model.UserEntity;
import vip.mate.auth.service.ActorResolver;
import vip.mate.auth.service.AuthService;
import vip.mate.semantic.security.SemanticPrincipalResolver;
import vip.mate.semantic.web.SemanticApiException;

class SemanticPrincipalResolverTest {
    private final AuthService auth = mock(AuthService.class);
    private final SemanticPrincipalResolver resolver =
            new SemanticPrincipalResolver(new ActorResolver(auth));

    private ToolContext context(Long workspace, Long actor) {
        return new ToolContext(
                Map.of(
                        ChatOrigin.CTX_KEY,
                        ChatOrigin.web("c", "untrusted", workspace, null, null, actor)));
    }

    @Test
    void requesterTextDoesNotBecomeAuthenticatedToolIdentity() {
        var e =
                assertThrows(
                        SemanticApiException.class, () -> resolver.requireTool(context(1L, null)));
        assertEquals(401, e.status());
        assertEquals("UNAUTHENTICATED", e.code());
        assertEquals("Authenticated web origin required", e.getMessage());
        verifyNoInteractions(auth);
    }

    @Test
    void workspaceValidationStillPrecedesAccountLookup() {
        var e =
                assertThrows(
                        SemanticApiException.class, () -> resolver.requireTool(context(null, 9L)));
        assertEquals(400, e.status());
        assertEquals("WORKSPACE_REQUIRED", e.code());
        verifyNoInteractions(auth);
    }

    @Test
    void boundToolIdentityIsRevalidatedAndIdsRemainStrings() {
        var user = new UserEntity();
        user.setId(9L);
        user.setEnabled(true);
        user.setDeleted(null);
        when(auth.findById(9L)).thenReturn(user);
        var result = resolver.requireTool(context(1L, 9L));
        assertEquals("9", result.userId());
        assertEquals("1", result.workspaceId());
        user.setEnabled(false);
        var e =
                assertThrows(
                        SemanticApiException.class, () -> resolver.requireTool(context(1L, 9L)));
        assertEquals(401, e.status());
        assertEquals("Active user required", e.getMessage());
    }
}
