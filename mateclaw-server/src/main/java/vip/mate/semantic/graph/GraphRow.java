package vip.mate.semantic.graph;

import lombok.Data;

@Data
public class GraphRow {
    private String id;
    private Long workspaceId;
    private Long kbId;
    private String ontologyRevisionId;
    private Boolean enabled;
    private Long mutationVersion;
    private java.time.LocalDateTime createdAt;
    private java.time.LocalDateTime updatedAt;
}
