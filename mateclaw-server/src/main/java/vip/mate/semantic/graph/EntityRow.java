package vip.mate.semantic.graph;

import lombok.Data;

@Data
public class EntityRow {
    private String id;
    private String graphId;
    private String iri;
    private String iriDigest;
    private String assertedTypesJson;
    private String displayName;
    private String status;
    private String createdBy;
    private java.time.LocalDateTime createdAt;
}
