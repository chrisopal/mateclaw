CREATE TABLE mate_semantic_source_change_run (
 id VARCHAR(36) PRIMARY KEY, graph_id VARCHAR(36) NOT NULL, operation_id VARCHAR(128) NOT NULL,
 status VARCHAR(16) NOT NULL, expected_graph_version BIGINT, request_digest VARCHAR(64) NOT NULL, baseline_json CLOB NOT NULL, created_by VARCHAR(32) NOT NULL,
 created_at TIMESTAMP(6) NOT NULL, updated_at TIMESTAMP(6) NOT NULL,
 CONSTRAINT uq_sem_source_change_run_operation UNIQUE(graph_id,operation_id)
);
CREATE INDEX idx_sem_source_change_run_graph ON mate_semantic_source_change_run(graph_id,created_at);

CREATE TABLE mate_semantic_source_change_lock (
 graph_id VARCHAR(36) NOT NULL, source_kind VARCHAR(24) NOT NULL, source_ref VARCHAR(64) NOT NULL,
 touched_at TIMESTAMP(6) NOT NULL, PRIMARY KEY(graph_id,source_kind,source_ref)
);

CREATE TABLE mate_semantic_source_change (
 id VARCHAR(36) PRIMARY KEY, run_id VARCHAR(36) NOT NULL, graph_id VARCHAR(36) NOT NULL,
 source_kind VARCHAR(24) NOT NULL, source_ref VARCHAR(64) NOT NULL,
 old_snapshot_id VARCHAR(36), new_snapshot_id VARCHAR(36), old_digest VARCHAR(64),
 new_digest VARCHAR(64) NOT NULL, source_state VARCHAR(24) NOT NULL,
 observed_graph_version BIGINT NOT NULL, created_by VARCHAR(32) NOT NULL, created_at TIMESTAMP(6) NOT NULL,
 CONSTRAINT uq_sem_source_change_run_source UNIQUE(run_id,source_kind,source_ref)
);
CREATE INDEX idx_sem_source_change_graph_source ON mate_semantic_source_change(graph_id,source_kind,source_ref,created_at);

CREATE TABLE mate_semantic_source_change_item (
 id VARCHAR(36) PRIMARY KEY, change_id VARCHAR(36) NOT NULL, graph_id VARCHAR(36) NOT NULL,
 source_kind VARCHAR(24) NOT NULL, source_ref VARCHAR(64) NOT NULL,
 item_kind VARCHAR(24) NOT NULL, item_id VARCHAR(36) NOT NULL, item_revision BIGINT,
 old_snapshot_id VARCHAR(36), new_snapshot_id VARCHAR(36), old_digest VARCHAR(64), new_digest VARCHAR(64) NOT NULL,
 source_state VARCHAR(24) NOT NULL, review_state VARCHAR(16) NOT NULL,
 decision VARCHAR(24), reason VARCHAR(2000), decision_operation_id VARCHAR(128), decision_request_digest VARCHAR(64),
 observed_graph_version BIGINT NOT NULL, created_by VARCHAR(32) NOT NULL, created_at TIMESTAMP(6) NOT NULL,
 reviewed_by VARCHAR(32), reviewed_at TIMESTAMP(6),
 CONSTRAINT uq_sem_source_change_item UNIQUE(change_id,item_kind,item_id,item_revision,old_snapshot_id)
);
CREATE UNIQUE INDEX uq_sem_source_change_decision_operation ON mate_semantic_source_change_item(graph_id,decision_operation_id);
CREATE INDEX idx_sem_source_change_item_pending ON mate_semantic_source_change_item(graph_id,review_state,created_at);
