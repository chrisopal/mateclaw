CREATE TABLE mate_semantic_graph_migration_plan (
 id VARCHAR(36) PRIMARY KEY, graph_id VARCHAR(36) NOT NULL, operation_id VARCHAR(128) NOT NULL,
 request_digest VARCHAR(64) NOT NULL, plan_digest VARCHAR(64) NOT NULL, status VARCHAR(24) NOT NULL,
 expected_graph_version BIGINT NOT NULL, source_revision_id VARCHAR(36) NOT NULL, target_revision_id VARCHAR(36) NOT NULL,
 source_version INTEGER NOT NULL, target_version INTEGER NOT NULL,
 source_document_digest VARCHAR(64) NOT NULL, target_document_digest VARCHAR(64) NOT NULL,
 source_import_lock_digest VARCHAR(64) NOT NULL, target_import_lock_digest VARCHAR(64) NOT NULL,
 mapping_json TEXT NOT NULL, payload_json TEXT NOT NULL, impact_json TEXT NOT NULL,
 approved_operation_id VARCHAR(128), approved_request_digest VARCHAR(64),
 executed_operation_id VARCHAR(128), executed_request_digest VARCHAR(64),
 rollback_operation_id VARCHAR(128), rollback_request_digest VARCHAR(64),
 executed_graph_version BIGINT, rollback_graph_version BIGINT,
 created_by VARCHAR(32) NOT NULL, approved_by VARCHAR(32),
 created_at TIMESTAMP(6) NOT NULL, approved_at TIMESTAMP(6), executed_at TIMESTAMP(6), rolled_back_at TIMESTAMP(6),
 CONSTRAINT uq_sem_graph_migration_operation UNIQUE(graph_id,operation_id),
 CONSTRAINT uq_sem_graph_migration_digest UNIQUE(graph_id,plan_digest)
);
CREATE INDEX idx_sem_graph_migration_graph ON mate_semantic_graph_migration_plan(graph_id,created_at);
