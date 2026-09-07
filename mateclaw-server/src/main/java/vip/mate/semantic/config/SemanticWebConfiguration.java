package vip.mate.semantic.config;

import jakarta.servlet.http.*;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.*;

import vip.mate.semantic.security.SemanticAccessService;
import vip.mate.workspace.core.annotation.RequireWorkspaceRole;

/** Runs before the legacy workspace interceptor, which defaults absent scope to workspace 1. */
@Configuration
public class SemanticWebConfiguration implements WebMvcConfigurer {
    private final SemanticAccessService access;

    public SemanticWebConfiguration(SemanticAccessService access) {
        this.access = access;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(
                        new HandlerInterceptor() {
                            @Override
                            public boolean preHandle(
                                    HttpServletRequest request,
                                    HttpServletResponse response,
                                    Object handler) {
                                if (handler instanceof HandlerMethod method) {
                                    var role =
                                            method.getMethodAnnotation(RequireWorkspaceRole.class);
                                    if (role != null)
                                        access.require(
                                                request.getHeader("X-Workspace-Id"), role.value());
                                }
                                return true;
                            }
                        })
                .addPathPatterns("/api/v1/semantic/**")
                .order(-100);
    }
}
