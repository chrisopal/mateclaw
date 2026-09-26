package vip.mate.bidding;

import java.util.Set;
import org.springframework.ai.chat.model.ToolContext;
import vip.mate.agent.execution.ProjectExecutionOptions;
import vip.mate.agent.execution.ProjectToolPolicy;

/** Server-owned identity carrier and fixed tool boundary for bidding graph calls. */
public final class BiddingToolScope implements ProjectToolPolicy {
    private static final long serialVersionUID = 1L;
    private static final Set<String> TOOL_NAMES = Set.of("load_skill", "readSkillFile",
            "bidding_read_source", "bidding_read_sources", "bidding_read_material", "bidding_export_document");
    private final BiddingTypes.Claim claim;
    public BiddingToolScope(BiddingTypes.Claim claim) {
        this.claim = claim;
    }

    public BiddingTypes.Claim claim() { return claim; }

    public static BiddingTypes.Claim claim(ToolContext context) {
        Object raw = context == null || context.getContext() == null ? null
                : context.getContext().get(ProjectExecutionOptions.TOOL_CONTEXT_KEY);
        if (raw instanceof ProjectExecutionOptions options && options.toolPolicy() instanceof BiddingToolScope scope) {
            return scope.claim;
        }
        throw BiddingAccess.error(403, "CLAIM_REQUIRED", "Trusted active task claim is required");
    }

    public static void require(BiddingTypes.Claim claim, String toolName, String arguments) {
        if (claim == null || !TOOL_NAMES.contains(toolName))
            throw BiddingAccess.error(403, "TOOL_NOT_ALLOWED", "Tool is not allowed for this task");
        if (arguments != null && arguments.length() > 64_000)
            throw BiddingAccess.error(422, "TOOL_ARGUMENT_LIMIT", "Tool arguments exceed the task limit");
    }

    @Override public void require(String toolName, String arguments) {
        require(claim, toolName, arguments);
    }
}
