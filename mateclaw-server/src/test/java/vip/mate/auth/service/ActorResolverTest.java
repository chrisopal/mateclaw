package vip.mate.auth.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import vip.mate.auth.model.UserEntity;

class ActorResolverTest {
    private final AuthService auth = mock(AuthService.class);
    private final ActorResolver actors = new ActorResolver(auth);

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private UserEntity user() {
        var u = new UserEntity();
        u.setId(9L);
        u.setEnabled(true);
        u.setDeleted(0);
        return u;
    }

    @Test
    void anonymousAndUnauthenticatedNeverResolveAccounts() {
        assertEquals(
                ActorResolver.Reason.AUTHENTICATION_REQUIRED,
                assertThrows(ActorResolver.Denied.class, actors::requireCurrent).reason());
        for (var identity :
                List.of(
                        UsernamePasswordAuthenticationToken.unauthenticated("alice", ""),
                        UsernamePasswordAuthenticationToken.authenticated(
                                "anonymousUser", "", List.of()))) {
            SecurityContextHolder.getContext().setAuthentication(identity);
            assertEquals(
                    ActorResolver.Reason.AUTHENTICATION_REQUIRED,
                    assertThrows(ActorResolver.Denied.class, actors::requireCurrent).reason());
        }
        verifyNoInteractions(auth);
    }

    @Test
    void boundActorIgnoresAmbientUsernameAndRechecksDisabling() {
        var current = user();
        when(auth.findById(9L)).thenReturn(current);
        SecurityContextHolder.getContext()
                .setAuthentication(
                        UsernamePasswordAuthenticationToken.authenticated(
                                "different-user", "", List.of()));
        assertSame(current, actors.requireActiveId(9L));
        current.setEnabled(false);
        assertEquals(
                ActorResolver.Reason.ACTIVE_USER_REQUIRED,
                assertThrows(ActorResolver.Denied.class, () -> actors.requireActiveId(9L))
                        .reason());
        verify(auth, never()).findByUsername(anyString());
        verify(auth, times(2)).findById(9L);
    }

    @Test
    void currentLookupRequiresActiveAccountWithLegacyNullDeletedCompatibility() {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        UsernamePasswordAuthenticationToken.authenticated("alice", "", List.of()));
        when(auth.findByUsername("alice")).thenReturn(null);
        assertThrows(ActorResolver.Denied.class, actors::requireCurrent);
        var current = user();
        when(auth.findByUsername("alice")).thenReturn(current);
        current.setDeleted(null);
        assertSame(current, actors.requireCurrent());
        current.setDeleted(1);
        assertThrows(ActorResolver.Denied.class, actors::requireCurrent);
        current.setDeleted(0);
        current.setEnabled(null);
        assertThrows(ActorResolver.Denied.class, actors::requireCurrent);
    }

    @Test
    void hostBeanDoesNotDependOnAnyWorkbenchModule() {
        new ApplicationContextRunner()
                .withUserConfiguration(ActorResolver.class)
                .withBean(AuthService.class, () -> auth)
                .withPropertyValues(
                        "mateclaw.semantic.enabled=false",
                        "mateclaw.presales.enabled=false",
                        "mateclaw.bidding.enabled=false")
                .run(c -> assertNotNull(c.getBean(ActorResolver.class)));
    }
}
