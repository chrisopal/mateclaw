package vip.mate.semantic.ontology;

import lombok.Data;

@Data
public class OntologyRevisionRow {
    private String id;
    private String ontologyId;
    private Integer version;
    private Long draftVersion;
    private String revisionState;
    private Integer draftSlot;
    private String name;
    private String description;
    private String definitionJson;
    private String baseRevisionId;
    private Boolean availableForNewBindings;
    private java.time.LocalDateTime publishedAt;
    private String publishedBy;
    private String publicationNote;
}
