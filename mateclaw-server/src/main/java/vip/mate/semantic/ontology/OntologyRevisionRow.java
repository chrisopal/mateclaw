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
    private String documentText;
    private String documentSyntax;
    private String documentDigest;
    private String ontologyIri;
    private String versionIri;
    private String importLockDigest;
    private String modelSchema;
    private String importsJson;
    private String policyJson;
    private String baseRevisionId;
    private Boolean availableForNewBindings;
    private java.time.LocalDateTime publishedAt;
    private String publishedBy;
    private String publicationNote;
}
