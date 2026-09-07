package vip.mate.semantic.graph;

import lombok.Data;

@Data
public class GraphOntologyRevisionRow {
    private String id;
    private String ontologyId;
    private Long workspaceId;
    private Integer version;
    private String revisionState;
    private String definitionJson;
    private Boolean availableForNewBindings;
}
