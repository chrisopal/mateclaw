package vip.mate.semantic.tool;

import java.time.Instant;
import java.util.Locale;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import vip.mate.semantic.core.reasoning.ReasoningRequest.AssertionScope;
import vip.mate.semantic.core.reasoning.ReasoningRequest.TaskKind;
import vip.mate.semantic.reasoning.SemanticReasoningDtos.Request;
import vip.mate.semantic.reasoning.SemanticReasoningDtos.Result;
import vip.mate.semantic.reasoning.SemanticReasoningService;
import vip.mate.semantic.security.SemanticPrincipalResolver;

/** Read-only, version-pinned OWL reasoning for an authorized agent graph. */
@Component
@ConditionalOnProperty(prefix = "mateclaw.semantic", name = "enabled", havingValue = "true")
public class SemanticReasoningTool {
    private final SemanticPrincipalResolver principals;
    private final SemanticReasoningService reasoning;

    public SemanticReasoningTool(SemanticPrincipalResolver principals, SemanticReasoningService reasoning) {
        this.principals = principals;
        this.reasoning = reasoning;
    }

    @Tool(description = "Run bounded read-only OWL reasoning against an authorized graph pinned to one ontology revision. The default scope is TBOX_ONLY and never treats ontology ABox as accepted business facts. Use ONTOLOGY_ABOX, ACCEPTED_FACTS, or ONTOLOGY_ABOX_AND_ACCEPTED_FACTS explicitly. Accepted facts are the current supported evidence snapshot at asOf; the result includes revision, mutation, input digest and fact provenance. Results can be rejected as stale if the graph or evidence changes while reasoning runs. Explanations are UNAVAILABLE unless explicit premises are returned.")
    public Result semantic_reason(
            String graphId,
            @ToolParam(required = false, description = "TBOX_ONLY (default), ONTOLOGY_ABOX, ACCEPTED_FACTS, or ONTOLOGY_ABOX_AND_ACCEPTED_FACTS") String scope,
            @ToolParam(description = "CONSISTENCY, CLASSIFICATION, INSTANCE_TYPES, or AXIOM_ENTAILMENT") String task,
            @ToolParam(required = false, description = "Absolute individual IRI for INSTANCE_TYPES") String individualIri,
            @ToolParam(required = false, description = "One Functional Syntax axiom for AXIOM_ENTAILMENT") String axiomFunctionalSyntax,
            @ToolParam(required = false, description = "ISO instant; defaults to now") Instant asOf,
            ToolContext context) {
        var principal = principals.requireTool(context);
        return reasoning.reason(principal.workspaceId(), principal.userId(), principal.agentId(), graphId,
                new Request(parseScope(scope), parseTask(task), individualIri, axiomFunctionalSyntax, asOf));
    }

    private static AssertionScope parseScope(String value) {
        if (value == null || value.isBlank()) return AssertionScope.TBOX_ONLY;
        try { return AssertionScope.valueOf(value.trim().toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException exception) { throw new IllegalArgumentException("Unsupported reasoning scope"); }
    }

    private static TaskKind parseTask(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Reasoning task is required");
        try { return TaskKind.valueOf(value.trim().toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException exception) { throw new IllegalArgumentException("Unsupported reasoning task"); }
    }
}
