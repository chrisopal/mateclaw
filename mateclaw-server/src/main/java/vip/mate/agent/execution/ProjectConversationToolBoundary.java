package vip.mate.agent.execution;

import java.util.Set;
import vip.mate.agent.context.ChatOrigin;

/** Authorization boundary for legacy project-shaped conversations. */
public interface ProjectConversationToolBoundary {
    Decision evaluate(String toolName, String arguments, ChatOrigin origin);

    /** Tools that may be disclosed while building a project-scoped catalog. */
    default Set<String> visibleTools() {
        return Set.of();
    }

    /** Fail closed for legacy construction when the optional boundary is absent. */
    static Decision failClosed(String toolName, ChatOrigin origin) {
        if (origin != null
                && origin.conversationId() != null
                && origin.conversationId().startsWith("presales:")) {
            return Decision.deny("PRESALES_PROJECT_SCOPE: server project policy is unavailable.");
        }
        return Decision.allow();
    }

    record Decision(boolean allowed, String reason) {
        public static Decision allow() {
            return new Decision(true, "");
        }

        public static Decision deny(String reason) {
            return new Decision(false, reason == null ? "Tool is not authorized" : reason);
        }
    }
}
