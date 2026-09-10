-- OWL-02 expansion only. Legacy records remain explicitly unclassified until scoped RESET.
-- This migration never translates legacy JSON or deletes customer data.
ALTER TABLE mate_semantic_ontology_revision ADD COLUMN document_text LONGTEXT;
ALTER TABLE mate_semantic_ontology_revision ADD COLUMN document_syntax VARCHAR(32);
ALTER TABLE mate_semantic_ontology_revision ADD COLUMN document_digest VARCHAR(64);
ALTER TABLE mate_semantic_ontology_revision ADD COLUMN ontology_iri VARCHAR(2048);
ALTER TABLE mate_semantic_ontology_revision ADD COLUMN version_iri VARCHAR(2048);
ALTER TABLE mate_semantic_ontology_revision ADD COLUMN import_lock_digest VARCHAR(64);
ALTER TABLE mate_semantic_ontology_revision ADD COLUMN model_schema VARCHAR(32);
ALTER TABLE mate_semantic_ontology_revision ADD COLUMN imports_json LONGTEXT;
ALTER TABLE mate_semantic_ontology_revision ADD COLUMN policy_json LONGTEXT;
ALTER TABLE mate_semantic_ontology_revision MODIFY COLUMN definition_json LONGTEXT NULL;
ALTER TABLE mate_semantic_entity ADD COLUMN iri VARCHAR(2048);
ALTER TABLE mate_semantic_entity ADD COLUMN iri_digest VARCHAR(64);
ALTER TABLE mate_semantic_entity ADD COLUMN asserted_types_json LONGTEXT;
ALTER TABLE mate_semantic_entity ADD CONSTRAINT uq_sem_entity_iri UNIQUE(graph_id,iri_digest);
ALTER TABLE mate_semantic_entity MODIFY COLUMN type_key VARCHAR(128) NULL;

-- Content is private to a workspace even when two workspaces import identical bytes.
CREATE TABLE mate_semantic_import_artifact (
 id VARCHAR(36) PRIMARY KEY, workspace_id BIGINT NOT NULL,
 document_digest VARCHAR(64) NOT NULL, document_syntax VARCHAR(32) NOT NULL,
 document_text LONGTEXT NOT NULL, ontology_iri VARCHAR(2048), version_iri VARCHAR(2048),
 created_at TIMESTAMP(6) NOT NULL,
 CONSTRAINT uq_sem_import_artifact UNIQUE(workspace_id,document_digest)
);

-- Regenerable index. Complete document_text remains the semantic authority.
CREATE TABLE mate_semantic_ontology_axiom (
 revision_id VARCHAR(36) NOT NULL, axiom_id VARCHAR(64) NOT NULL,
 axiom_kind VARCHAR(64) NOT NULL, axiom_text LONGTEXT NOT NULL, signature_json LONGTEXT NOT NULL,
 PRIMARY KEY(revision_id,axiom_id),
 CONSTRAINT fk_sem_axiom_revision FOREIGN KEY(revision_id) REFERENCES mate_semantic_ontology_revision(id)
);

-- M7 provenance is structural, never parsed out of the label/description string.
-- Source snapshot permissions and shared references are checked by the application.
CREATE TABLE mate_semantic_axiom_source (
 id VARCHAR(36) PRIMARY KEY, revision_id VARCHAR(36) NOT NULL, axiom_id VARCHAR(64) NOT NULL,
 source_snapshot_id VARCHAR(36), source_digest VARCHAR(64), exact_quote LONGTEXT,
 start_code_point INTEGER, end_code_point INTEGER,
 origin VARCHAR(24) NOT NULL, review_state VARCHAR(24) NOT NULL,
 created_by VARCHAR(32) NOT NULL, created_at TIMESTAMP(6) NOT NULL,
 CONSTRAINT fk_sem_axiom_source FOREIGN KEY(revision_id,axiom_id)
  REFERENCES mate_semantic_ontology_axiom(revision_id,axiom_id),
 CONSTRAINT ck_sem_axiom_quote_range CHECK (
  (start_code_point IS NULL AND end_code_point IS NULL) OR
  (start_code_point IS NOT NULL AND end_code_point IS NOT NULL AND start_code_point>=0 AND end_code_point>=start_code_point))
);
CREATE INDEX idx_sem_axiom_source_snapshot ON mate_semantic_axiom_source(source_snapshot_id);
