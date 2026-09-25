package vip.mate.agent.execution;

/** Runtime authorization hook for tools used by a server-owned project task. */
@FunctionalInterface
public interface ProjectToolPolicy extends java.io.Serializable {
    void require(String toolName, String arguments);

    /** Service-side revalidation hook; never store its implementation in graph state. */
    @FunctionalInterface
    interface Revalidator {
        void requireActive(ProjectExecutionOptions options);
    }
}
