package vip.mate.agent.execution;

import java.util.Map;
import java.util.Set;

/** Immutable, server-created constraints for one isolated project task run. */
public record ProjectExecutionOptions(
        String attemptId, String modelConfigId, String configDigest, String skillName, String skillDigest,
        Map<String, String> skillFiles, Set<String> allowedTools, ProjectToolPolicy toolPolicy,
        int internalRetryLimit, boolean allowFallback, boolean injectMemory, int maxIterations)
        implements java.io.Serializable {
    public static final String TOOL_CONTEXT_KEY = "mateclaw.projectExecutionOptions";
    public ProjectExecutionOptions {
        skillFiles = skillFiles == null ? Map.of() : Map.copyOf(skillFiles);
        allowedTools = allowedTools == null ? Set.of() : Set.copyOf(allowedTools);
        if (internalRetryLimit < 0 || maxIterations < 1) throw new IllegalArgumentException("Invalid project execution limits");
        if (toolPolicy == null) throw new IllegalArgumentException("Project tool policy is required");
    }
}
