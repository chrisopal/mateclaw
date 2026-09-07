package vip.mate.semantic.security;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import vip.mate.auth.model.UserEntity;
import vip.mate.auth.service.AuthService;
import vip.mate.semantic.web.SemanticApiException;

@Service
public class SemanticPrincipalResolver {
    private final AuthService auth;

    public SemanticPrincipalResolver(AuthService auth) {
        this.auth = auth;
    }

    public UserEntity require() {
        var identity = SecurityContextHolder.getContext().getAuthentication();
        if (identity == null
                || !identity.isAuthenticated()
                || "anonymousUser".equals(identity.getName()))
            throw new SemanticApiException(401, "UNAUTHENTICATED", "Authentication required");
        var user = auth.findByUsername(identity.getName());
        if (user == null
                || !Boolean.TRUE.equals(user.getEnabled())
                || (user.getDeleted() != null && user.getDeleted() != 0))
            throw new SemanticApiException(401, "UNAUTHENTICATED", "Active user required");
        return user;
    }
}
