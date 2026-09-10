-- M7 immutable source snapshots independent of business graph existence.
CREATE TABLE mate_semantic_ontology_source_snapshot (
 id VARCHAR(36) PRIMARY KEY, workspace_id BIGINT NOT NULL, kb_id BIGINT NOT NULL,
 source_ref VARCHAR(32) NOT NULL, source_title VARCHAR(512) NOT NULL,
 source_text LONGTEXT NOT NULL, source_digest VARCHAR(64) NOT NULL,
 captured_by VARCHAR(32) NOT NULL, captured_at TIMESTAMP(6) NOT NULL
);
ALTER TABLE mate_semantic_axiom_source ADD CONSTRAINT fk_sem_axiom_source_snapshot
 FOREIGN KEY(source_snapshot_id) REFERENCES mate_semantic_ontology_source_snapshot(id);
CREATE TABLE mate_semantic_ontology_source_review (
 id VARCHAR(36) PRIMARY KEY, binding_id VARCHAR(36) NOT NULL,
 observed_digest VARCHAR(64) NOT NULL, source_state VARCHAR(24) NOT NULL,
 review_state VARCHAR(24) NOT NULL, decision VARCHAR(24), reason TEXT,
 reviewed_by VARCHAR(32), reviewed_at TIMESTAMP(6), created_at TIMESTAMP(6) NOT NULL,
 CONSTRAINT fk_sem_source_review_binding FOREIGN KEY(binding_id) REFERENCES mate_semantic_axiom_source(id),
 CONSTRAINT uq_sem_source_review_observed UNIQUE(binding_id,observed_digest)
);
