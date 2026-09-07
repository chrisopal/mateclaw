package vip.mate.semantic.web;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import java.time.Instant;
import java.util.List;

public final class GraphDtos {
    private GraphDtos() {}

    public enum BindingAction { ENABLE, DISABLE, REBIND }

    public record BindRequest(BindingAction action, String revisionId, Long expectedGraphVersion) {}
    public record CreateEntity(String typeKey, String displayName) {}
    public record Binding(
            String graphId,
            String workspaceId,
            String knowledgeBaseId,
            String ontologyRevisionId,
            int ontologyVersion,
            boolean enabled,
            @JsonSerialize(using = SemanticCounterSerializer.class) long graphVersion,
            boolean empty,
            @JsonFormat(shape = JsonFormat.Shape.STRING) Instant updatedAt) {}
    public record EntityView(
            String id, String graphId, String typeKey, String displayName, String status,
            @JsonFormat(shape = JsonFormat.Shape.STRING) Instant createdAt) {}
    public record EntityPage(List<EntityView> items) {}
}
