package vip.mate.semantic.ontology;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Regenerable projection written only inside the authoritative revision transaction. */
@Component
public final class OntologyAxiomIndex {
    private final JdbcTemplate jdbc;
    private final OntologyWireMapper wire;
    public OntologyAxiomIndex(JdbcTemplate jdbc,OntologyWireMapper wire){this.jdbc=jdbc;this.wire=wire;}
    public void synchronize(OntologyRevisionRow row){
        var parsed=wire.parsed(row);
        if(!parsed.lockedImports().isEmpty()) {
            Long scope=jdbc.queryForObject("SELECT workspace_id FROM mate_semantic_ontology WHERE id=?",Long.class,row.getOntologyId());
            jdbc.queryForObject("SELECT id FROM mate_workspace WHERE id=? FOR UPDATE",Long.class,scope);
            for(var artifact:parsed.lockedImports()) {
                var existingArtifact=jdbc.queryForList("SELECT id FROM mate_semantic_import_artifact WHERE workspace_id=? AND document_digest=?",String.class,scope,artifact.contentDigest());
                if(existingArtifact.isEmpty()) jdbc.update("INSERT INTO mate_semantic_import_artifact(id,workspace_id,document_digest,document_syntax,document_text,ontology_iri,version_iri,created_at) VALUES(?,?,?,?,?,?,?,?)",
                    UUID.randomUUID().toString(),scope,artifact.contentDigest(),artifact.syntax().name(),artifact.documentText(),artifact.resolvedOntologyIri(),artifact.versionIri().orElse(null),java.time.LocalDateTime.now(java.time.ZoneOffset.UTC));
            }
        }
        var axioms=parsed.axioms();
        var existing=new HashSet<>(jdbc.query("SELECT axiom_id FROM mate_semantic_ontology_axiom WHERE revision_id=?",(rs,n)->rs.getString(1),row.getId()));
        var retained=new HashSet<String>();
        for(var axiom:axioms){
            retained.add(axiom.axiomId());
            if(existing.contains(axiom.axiomId())) continue;
            jdbc.update("INSERT INTO mate_semantic_ontology_axiom(revision_id,axiom_id,axiom_kind,axiom_text,signature_json) VALUES(?,?,?,?,?)",
                row.getId(),axiom.axiomId(),axiom.axiomType(),axiom.rendering(),wire.encode(axiom.signatureIris()));
        }
        existing.removeAll(retained);
        for(String id:existing){
            jdbc.update("DELETE FROM mate_semantic_ontology_source_review WHERE binding_id IN (SELECT id FROM mate_semantic_axiom_source WHERE revision_id=? AND axiom_id=?)",row.getId(),id);
            jdbc.update("DELETE FROM mate_semantic_axiom_source WHERE revision_id=? AND axiom_id=?",row.getId(),id);
            jdbc.update("DELETE FROM mate_semantic_ontology_axiom WHERE revision_id=? AND axiom_id=?",row.getId(),id);
        }
    }
    public void copyBindings(String sourceRevision,OntologyRevisionRow target) {
        for(var axiom:wire.parsed(target).axioms()) {
            var rows=jdbc.queryForList("SELECT b.* FROM mate_semantic_axiom_source b JOIN mate_semantic_ontology_axiom a ON a.revision_id=b.revision_id AND a.axiom_id=b.axiom_id WHERE b.revision_id=? AND a.axiom_text=?",sourceRevision,axiom.rendering());
            for(var source:rows) {
                String id=UUID.randomUUID().toString();
                jdbc.update("INSERT INTO mate_semantic_axiom_source(id,revision_id,axiom_id,source_snapshot_id,source_digest,exact_quote,start_code_point,end_code_point,origin,review_state,created_by,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                    id,target.getId(),axiom.axiomId(),source.get("source_snapshot_id"),source.get("source_digest"),source.get("exact_quote"),source.get("start_code_point"),source.get("end_code_point"),source.get("origin"),"PENDING",source.get("created_by"),java.time.LocalDateTime.now(java.time.ZoneOffset.UTC));
            }
        }
    }
    public void discard(String revisionId){
        jdbc.update("DELETE FROM mate_semantic_ontology_source_review WHERE binding_id IN (SELECT id FROM mate_semantic_axiom_source WHERE revision_id=?)",revisionId);
        jdbc.update("DELETE FROM mate_semantic_axiom_source WHERE revision_id=?",revisionId);
        jdbc.update("DELETE FROM mate_semantic_ontology_axiom WHERE revision_id=?",revisionId);
    }
}
