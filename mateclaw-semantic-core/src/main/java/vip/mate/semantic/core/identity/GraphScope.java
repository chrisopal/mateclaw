package vip.mate.semantic.core.identity;

import java.util.Objects;

import vip.mate.semantic.core.identity.SemanticIds.GraphId;
import vip.mate.semantic.core.identity.SemanticIds.KnowledgeBaseId;
import vip.mate.semantic.core.identity.SemanticIds.WorkspaceId;

/** Resource scope for a semantic graph. It is not an authorization credential. */
public record GraphScope(WorkspaceId workspaceId, KnowledgeBaseId kbId, GraphId graphId) {

    public GraphScope {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(kbId, "kbId");
        Objects.requireNonNull(graphId, "graphId");
    }
}
