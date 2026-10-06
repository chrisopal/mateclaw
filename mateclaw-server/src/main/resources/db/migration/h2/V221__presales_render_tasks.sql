-- Immutable CREATE_RELEASE input and the active attempt fence. Final responses remain
-- authoritative in mate_presales_operation; callers serialize both under the authority fence.
CREATE TABLE mate_presales_render_task (
 task_id VARCHAR(64) PRIMARY KEY, workspace_id VARCHAR(64) NOT NULL,
 actor_id VARCHAR(64) NOT NULL, operation_id VARCHAR(128) NOT NULL,
 project_id VARCHAR(64) NOT NULL, expected_version BIGINT NOT NULL,
 request_hash VARCHAR(67) NOT NULL, attempt_id VARCHAR(64) NOT NULL,
 attempt_no INTEGER NOT NULL, status VARCHAR(32) NOT NULL,
 input_json TEXT NOT NULL, lease_until TIMESTAMP(6) NOT NULL,
 created_at TIMESTAMP(6) NOT NULL, updated_at TIMESTAMP(6) NOT NULL,
 UNIQUE(workspace_id,actor_id,operation_id),
 UNIQUE(attempt_id)
);
