package vip.mate.semantic.core.architecturefixture;

import org.springframework.context.ApplicationContext;

/** Deliberate test-only framework dependency; excluded from production imports. */
public final class ForbiddenFrameworkDependency {
    public ApplicationContext framework;
}
