CREATE TABLE mate_semantic_source_snapshot (
 id VARCHAR(36) PRIMARY KEY, graph_id VARCHAR(36) NOT NULL, source_kind VARCHAR(24) NOT NULL,
 source_id VARCHAR(64) NOT NULL, source_title VARCHAR(256) NOT NULL, capture_version BIGINT NOT NULL,
 text_digest VARCHAR(64) NOT NULL, text_content TEXT NOT NULL, created_by VARCHAR(32) NOT NULL,
 created_at TIMESTAMP(6) NOT NULL, CONSTRAINT uq_sem_snapshot_source_version UNIQUE(graph_id,source_kind,source_id,capture_version)
);
CREATE INDEX idx_sem_snapshot_graph ON mate_semantic_source_snapshot(graph_id,created_at);
CREATE TABLE mate_semantic_import_job (
 id VARCHAR(36) PRIMARY KEY, graph_id VARCHAR(36) NOT NULL, source_kind VARCHAR(24) NOT NULL,
 source_id VARCHAR(64) NOT NULL, operation_id VARCHAR(128) NOT NULL, request_hash VARCHAR(64) NOT NULL,
 status VARCHAR(16) NOT NULL, snapshot_id VARCHAR(36), attempts INTEGER NOT NULL, error_message VARCHAR(512),
 lease_owner VARCHAR(64), lease_until TIMESTAMP(6), retry_of_job_id VARCHAR(36),
 created_by VARCHAR(32) NOT NULL, created_at TIMESTAMP(6) NOT NULL, updated_at TIMESTAMP(6) NOT NULL,
 CONSTRAINT uq_sem_import_operation UNIQUE(graph_id,operation_id)
);
CREATE INDEX idx_sem_import_status_lease ON mate_semantic_import_job(status,lease_until);
CREATE TABLE mate_semantic_evidence (
 id VARCHAR(36) PRIMARY KEY, graph_id VARCHAR(36) NOT NULL, snapshot_id VARCHAR(36) NOT NULL,
 operation_id VARCHAR(128) NOT NULL, start_codepoint INTEGER NOT NULL, end_codepoint INTEGER NOT NULL,
 exact_quote TEXT NOT NULL, created_by VARCHAR(32) NOT NULL, created_at TIMESTAMP(6) NOT NULL,
 CONSTRAINT uq_sem_evidence_operation UNIQUE(graph_id,operation_id)
);
CREATE INDEX idx_sem_evidence_snapshot ON mate_semantic_evidence(snapshot_id);
CREATE TABLE mate_semantic_statement (
 id VARCHAR(36) PRIMARY KEY, graph_id VARCHAR(36) NOT NULL, current_revision INTEGER NOT NULL,
 created_by VARCHAR(32) NOT NULL, created_at TIMESTAMP(6) NOT NULL
);
CREATE INDEX idx_sem_statement_graph ON mate_semantic_statement(graph_id);
CREATE TABLE mate_semantic_statement_revision (
 statement_id VARCHAR(36) NOT NULL, revision INTEGER NOT NULL, graph_id VARCHAR(36) NOT NULL,
 ontology_revision_id VARCHAR(36) NOT NULL, subject_id VARCHAR(36) NOT NULL,
 predicate_kind VARCHAR(16) NOT NULL, predicate_key VARCHAR(128) NOT NULL,
 review_status VARCHAR(16) NOT NULL, validity_kind VARCHAR(16) NOT NULL,
 valid_from TIMESTAMP(6), valid_to TIMESTAMP(6), value_type VARCHAR(16) NOT NULL,
 value_text TEXT, unit VARCHAR(32), target_entity_id VARCHAR(36), content_json TEXT NOT NULL,
 actor_id VARCHAR(32) NOT NULL, reason VARCHAR(1000), created_at TIMESTAMP(6) NOT NULL,
 PRIMARY KEY(statement_id,revision)
);
CREATE INDEX idx_sem_revision_graph_review ON mate_semantic_statement_revision(graph_id,review_status,subject_id,predicate_key);
CREATE TABLE mate_semantic_revision_evidence (
 statement_id VARCHAR(36) NOT NULL, revision INTEGER NOT NULL, evidence_id VARCHAR(36) NOT NULL,
 PRIMARY KEY(statement_id,revision,evidence_id)
);
CREATE TABLE mate_semantic_change_proposal (
 id VARCHAR(36) PRIMARY KEY, graph_id VARCHAR(36) NOT NULL, target_statement_id VARCHAR(36) NOT NULL,
 expected_revision INTEGER NOT NULL, operation_id VARCHAR(128) NOT NULL, payload_json TEXT NOT NULL,
 status VARCHAR(16) NOT NULL, result_revision INTEGER, proposed_by VARCHAR(32) NOT NULL, created_at TIMESTAMP(6) NOT NULL,
 CONSTRAINT uq_sem_change_operation UNIQUE(graph_id,operation_id)
);
CREATE TABLE mate_semantic_conflict (
 id VARCHAR(36) PRIMARY KEY, graph_id VARCHAR(36) NOT NULL, kind VARCHAR(40) NOT NULL,
 status VARCHAR(16) NOT NULL, left_statement_id VARCHAR(36) NOT NULL, left_revision INTEGER NOT NULL,
 right_statement_id VARCHAR(36) NOT NULL, right_revision INTEGER NOT NULL,
 resolution_json TEXT, resolved_by VARCHAR(32), resolved_at TIMESTAMP(6), created_at TIMESTAMP(6) NOT NULL
);
CREATE INDEX idx_sem_conflict_graph_status ON mate_semantic_conflict(graph_id,status);
CREATE TABLE mate_semantic_source_governance (
 graph_id VARCHAR(36) NOT NULL, source_kind VARCHAR(24) NOT NULL, source_id VARCHAR(64) NOT NULL,
 state VARCHAR(16) NOT NULL, actor_id VARCHAR(32) NOT NULL, reason VARCHAR(1000) NOT NULL, created_at TIMESTAMP(6) NOT NULL,
 PRIMARY KEY(graph_id,source_kind,source_id)
);
CREATE TABLE mate_semantic_snapshot_exclusion (
 graph_id VARCHAR(36) NOT NULL, snapshot_id VARCHAR(36) NOT NULL, actor_id VARCHAR(32) NOT NULL,
 reason VARCHAR(1000) NOT NULL, created_at TIMESTAMP(6) NOT NULL, PRIMARY KEY(graph_id,snapshot_id)
);
CREATE TABLE mate_semantic_mutation_command (
 id VARCHAR(36) PRIMARY KEY, graph_id VARCHAR(36) NOT NULL, operation_id VARCHAR(128) NOT NULL,
 kind VARCHAR(40) NOT NULL, payload_hash VARCHAR(64) NOT NULL, result_json TEXT NOT NULL,
 created_at TIMESTAMP(6) NOT NULL, CONSTRAINT uq_sem_mutation_operation UNIQUE(graph_id,operation_id)
);
