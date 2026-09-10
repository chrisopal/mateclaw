package vip.mate.semantic.graph;

/** Ontology revision with workspace ownership from the graph join. */
public class GraphOntologyRevisionRow extends vip.mate.semantic.ontology.OntologyRevisionRow {
    private Long workspaceId;
    public Long getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(Long workspaceId) { this.workspaceId = workspaceId; }
}
