package vip.mate.semantic.tool;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import vip.mate.semantic.query.SemanticQueryService;
import vip.mate.semantic.query.SemanticQueryDtos.SearchRequest;
import vip.mate.semantic.query.SemanticQueryDtos.SearchResult;
import vip.mate.semantic.security.SemanticPrincipalResolver;

/** Read-only evidence retrieval through the same trusted-fact query used by the console. */
@Component
@ConditionalOnProperty(prefix = "mateclaw.semantic", name = "enabled", havingValue = "true")
public class SemanticTool {
    private final SemanticPrincipalResolver principals;
    private final SemanticQueryService queries;

    public SemanticTool(SemanticPrincipalResolver principals, SemanticQueryService queries) {
        this.principals = principals;
        this.queries = queries;
    }

    @Tool(description = "Search accepted, supported semantic facts in a graph. Returns fact IDs, revisions, evidence IDs and a trace ID. Cite these references in answers.")
    public SearchResult semantic_search(String graphId, String query,
            @ToolParam(required = false, description = "Maximum results, 1 to 100; defaults to 20") Integer limit,
            ToolContext context) {
        var principal = principals.requireTool(context);
        return queries.searchAsActor(principal.workspaceId(), principal.userId(), graphId,
                new SearchRequest(query, limit, null));
    }
}
