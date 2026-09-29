package vip.mate.agent.execution;

/** Business-owned validator for one server-created project execution scope. */
public interface ProjectExecutionRevalidationProvider {
    boolean supports(ProjectExecutionOptions options);

    void requireActive(ProjectExecutionOptions options);
}
