package vip.mate.agent.architecturefixture;

import vip.mate.agent.execution.ProjectExecutionOptions;
import vip.mate.agent.execution.ProjectToolPolicy;
import vip.mate.presales.PresalesService;

/** Deliberate test-only bytecode inputs; excluded from production architecture imports. */
public final class RuntimeCanaries {
    private RuntimeCanaries() {}

    public static final class ForbiddenWorkbenchDependency {
        public PresalesService concreteWorkbench;
    }

    public static final class AllowedExecutionContract {
        public ProjectExecutionOptions options;
        public ProjectToolPolicy policy;
    }
}
