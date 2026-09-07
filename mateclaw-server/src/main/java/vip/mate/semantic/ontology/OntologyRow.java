package vip.mate.semantic.ontology;

import lombok.Data;

@Data
public class OntologyRow {
    private String id;
    private Long workspaceId;
    private String name;
    private String description;
    private Integer latestVersion;
    private String latestRevisionId;
    private String draftId;
    private Long draftCounter;
    private java.time.LocalDateTime updatedAt;
}
