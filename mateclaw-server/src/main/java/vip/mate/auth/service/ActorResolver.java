package vip.mate.auth.service;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import vip.mate.auth.model.UserEntity;

/**
 * Revalidates host-authenticated identities against current account state; never caches authority.
 */
@Service
public class ActorResolver {
    private final AuthService auth;

    public ActorResolver(AuthService auth) {
        this.auth = auth;
    }

    public UserEntity requireCurrent() {
        var identity = SecurityContextHolder.getContext().getAuthentication();
        if (identity == null
                || !identity.isAuthenticated()
                || "anonymousUser".equals(identity.getName()))
            throw new Denied(Reason.AUTHENTICATION_REQUIRED);
        return active(auth.findByUsername(identity.getName()));
    }

    /** ID must come from authenticated host scope, never caller-supplied tool JSON. */
    public UserEntity requireActiveId(long actorId) {
        return active(auth.findById(actorId));
    }

    public UserEntity requireActiveIdForUpdate(long actorId) {
        return active(auth.findByIdForUpdate(actorId));
    }

    private static UserEntity active(UserEntity user) {
        if (user == null
                || !Boolean.TRUE.equals(user.getEnabled())
                || (user.getDeleted() != null && user.getDeleted() != 0))
            throw new Denied(Reason.ACTIVE_USER_REQUIRED);
        return user;
    }

    public enum Reason {
        AUTHENTICATION_REQUIRED,
        ACTIVE_USER_REQUIRED
    }

    public static final class Denied extends RuntimeException {
        private final Reason reason;

        private Denied(Reason reason) {
            super(reason.name());
            this.reason = reason;
        }

        public Reason reason() {
            return reason;
        }
    }
}
