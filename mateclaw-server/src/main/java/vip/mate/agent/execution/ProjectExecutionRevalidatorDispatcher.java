package vip.mate.agent.execution;

import java.util.List;
import org.springframework.stereotype.Component;

/** The only runtime revalidator bean; project adapters must match exactly once. */
@Component
public final class ProjectExecutionRevalidatorDispatcher implements ProjectToolPolicy.Revalidator {
    private final List<ProjectExecutionRevalidationProvider> providers;

    public ProjectExecutionRevalidatorDispatcher(
            List<ProjectExecutionRevalidationProvider> providers) {
        this.providers = List.copyOf(providers);
    }

    @Override
    public void requireActive(ProjectExecutionOptions options) {
        if (options == null) throw new IllegalStateException("Project execution scope is required");
        ProjectExecutionRevalidationProvider selected = null;
        for (ProjectExecutionRevalidationProvider provider : providers) {
            if (!provider.supports(options)) continue;
            if (selected != null)
                throw new IllegalStateException("Ambiguous project execution policy");
            selected = provider;
        }
        if (selected == null)
            throw new IllegalStateException("Project execution policy is unavailable");
        selected.requireActive(options);
    }
}
