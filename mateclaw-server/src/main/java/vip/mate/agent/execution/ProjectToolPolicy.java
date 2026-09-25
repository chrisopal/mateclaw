package vip.mate.agent.execution;

/** Runtime authorization hook for tools used by a server-owned project task. */
@FunctionalInterface
public interface ProjectToolPolicy extends java.io.Serializable {
    void require(String toolName, String arguments);
}
