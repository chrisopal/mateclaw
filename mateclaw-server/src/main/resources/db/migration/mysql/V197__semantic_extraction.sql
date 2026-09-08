-- M5 task lease gate and immutable extraction inputs; no credentials are stored.
CREATE TABLE mate_semantic_extraction_gate (id INTEGER PRIMARY KEY);
INSERT INTO mate_semantic_extraction_gate(id) VALUES(1);
CREATE TABLE mate_semantic_extraction_task (
 id VARCHAR(36) PRIMARY KEY, workspace_id VARCHAR(32) NOT NULL, graph_id VARCHAR(36) NOT NULL,
 operation_id VARCHAR(128) NOT NULL, request_hash VARCHAR(64) NOT NULL, payload_json LONGTEXT NOT NULL,
 status VARCHAR(16) NOT NULL, version BIGINT NOT NULL, cancel_requested BOOLEAN NOT NULL,
 generation BIGINT NOT NULL, attempts INTEGER NOT NULL, lease_owner VARCHAR(128), lease_until TIMESTAMP(6),
 completed_chunks INTEGER NOT NULL, error_code VARCHAR(64), trace_id VARCHAR(36) NOT NULL,
 explanation VARCHAR(1000), created_at TIMESTAMP(6) NOT NULL, updated_at TIMESTAMP(6) NOT NULL,
 UNIQUE(workspace_id,graph_id,operation_id)
);
CREATE INDEX idx_sem_extraction_queue ON mate_semantic_extraction_task(status,lease_until,created_at);
CREATE TABLE mate_semantic_extraction_attempt (
 id VARCHAR(36) PRIMARY KEY, task_id VARCHAR(36) NOT NULL, attempt_number INTEGER NOT NULL,
 generation BIGINT NOT NULL, payload_json LONGTEXT NOT NULL, UNIQUE(task_id,attempt_number)
);
CREATE TABLE mate_semantic_extraction_suggestion (
 id VARCHAR(36) PRIMARY KEY, task_id VARCHAR(36) NOT NULL, attempt_id VARCHAR(36) NOT NULL,
 version BIGINT NOT NULL, status VARCHAR(16) NOT NULL, payload_json LONGTEXT NOT NULL
);
CREATE INDEX idx_sem_extraction_suggestions ON mate_semantic_extraction_suggestion(task_id,id);
CREATE TABLE mate_semantic_extraction_edit (
 suggestion_id VARCHAR(36) NOT NULL, version BIGINT NOT NULL, payload_json LONGTEXT NOT NULL,
 actor_id VARCHAR(32) NOT NULL, created_at TIMESTAMP(6) NOT NULL, PRIMARY KEY(suggestion_id,version)
);
CREATE TABLE mate_semantic_extraction_receipt (
 suggestion_id VARCHAR(36) NOT NULL, edit_version BIGINT NOT NULL, graph_id VARCHAR(36) NOT NULL,
 operation_id VARCHAR(128) NOT NULL, request_hash VARCHAR(64) NOT NULL, payload_json LONGTEXT NOT NULL,
 PRIMARY KEY(suggestion_id,edit_version), UNIQUE(graph_id,operation_id)
);
CREATE TABLE mate_semantic_extraction_submission_intent (
 suggestion_id VARCHAR(36) NOT NULL, edit_version BIGINT NOT NULL, graph_id VARCHAR(36) NOT NULL,
 operation_id VARCHAR(128) NOT NULL, request_hash VARCHAR(64) NOT NULL,
 PRIMARY KEY(suggestion_id,edit_version), UNIQUE(graph_id,operation_id)
);
CREATE TABLE mate_semantic_extraction_action (
 graph_id VARCHAR(36) NOT NULL, operation_id VARCHAR(128) NOT NULL, resource_id VARCHAR(36) NOT NULL,
 action VARCHAR(16) NOT NULL, request_hash VARCHAR(64) NOT NULL, result_json TEXT NOT NULL,
 PRIMARY KEY(graph_id,operation_id)
);
