CREATE TABLE mate_semantic_ontology_validation_report (
 id VARCHAR(36) PRIMARY KEY,
 workspace_id BIGINT NOT NULL,
 ontology_id VARCHAR(36) NOT NULL,
 draft_revision_id VARCHAR(36) NOT NULL,
 draft_version BIGINT NOT NULL,
 input_digest VARCHAR(64) NOT NULL,
 checks_json LONGTEXT NOT NULL,
 valid BOOLEAN NOT NULL,
 created_by VARCHAR(32) NOT NULL,
 created_at TIMESTAMP(6) NOT NULL,
 CONSTRAINT fk_sem_validation_ontology FOREIGN KEY (ontology_id) REFERENCES mate_semantic_ontology(id)
);
CREATE INDEX idx_sem_validation_latest ON mate_semantic_ontology_validation_report(ontology_id, created_at, id);
