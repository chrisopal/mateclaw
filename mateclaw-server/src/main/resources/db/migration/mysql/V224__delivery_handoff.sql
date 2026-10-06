CREATE TABLE mate_delivery_handoff (
    id VARCHAR(128) PRIMARY KEY,
    workspace_id VARCHAR(128) NOT NULL,
    actor_id VARCHAR(128) NOT NULL,
    operation_id VARCHAR(128) NOT NULL,
    project_id VARCHAR(128) NOT NULL,
    release_id VARCHAR(128) NOT NULL,
    digest VARCHAR(64) NOT NULL,
    snapshot_json LONGTEXT NOT NULL,
    CONSTRAINT uq_delivery_operation UNIQUE (workspace_id, actor_id, operation_id)
);
CREATE INDEX idx_delivery_release ON mate_delivery_handoff(workspace_id, project_id, release_id);
