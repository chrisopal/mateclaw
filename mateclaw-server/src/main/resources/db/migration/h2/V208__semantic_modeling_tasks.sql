CREATE TABLE mate_semantic_modeling_task (
 id VARCHAR(36) PRIMARY KEY,
 workspace_id BIGINT NOT NULL,
 operation_id VARCHAR(128) NOT NULL,
 request_digest VARCHAR(64) NOT NULL,
 ontology_id VARCHAR(36) NOT NULL,
 state_json CLOB NOT NULL,
 created_at TIMESTAMP(6) NOT NULL,
 updated_at TIMESTAMP(6) NOT NULL,
 CONSTRAINT uq_sem_modeling_create UNIQUE(workspace_id, operation_id)
);
CREATE INDEX idx_sem_modeling_workspace ON mate_semantic_modeling_task(workspace_id,created_at,id);
CREATE TABLE mate_semantic_modeling_operation (
 task_id VARCHAR(36) NOT NULL,
 operation_id VARCHAR(128) NOT NULL,
 request_digest VARCHAR(64) NOT NULL,
 result_json CLOB NOT NULL,
 PRIMARY KEY(task_id,operation_id)
);
