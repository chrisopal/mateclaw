CREATE TABLE mate_bidding_project (
 id VARCHAR(64) PRIMARY KEY, workspace_id VARCHAR(64) NOT NULL, project_id VARCHAR(64) NOT NULL,
 owner_id VARCHAR(64) NOT NULL, version INTEGER NOT NULL, name VARCHAR(200) NOT NULL, lot_name VARCHAR(200) NOT NULL,
 stage VARCHAR(32) NOT NULL, body_json TEXT NOT NULL, created_at TIMESTAMP NOT NULL, updated_at TIMESTAMP NOT NULL
);
CREATE INDEX idx_bidding_project_workspace ON mate_bidding_project(workspace_id,updated_at);
CREATE TABLE mate_bidding_source (
 id VARCHAR(64) PRIMARY KEY, workspace_id VARCHAR(64) NOT NULL, project_id VARCHAR(64) NOT NULL,
 source_id VARCHAR(64) NOT NULL, version INTEGER NOT NULL, kind VARCHAR(64) NOT NULL, digest VARCHAR(64) NOT NULL,
 content BYTEA NOT NULL, blocks_json TEXT NOT NULL, quality VARCHAR(32) NOT NULL, read_token VARCHAR(128), read_started_at TIMESTAMP,
 created_at TIMESTAMP NOT NULL, CONSTRAINT uq_bidding_source UNIQUE(project_id,source_id,version)
);
CREATE TABLE mate_bidding_head (
 workspace_id VARCHAR(64) NOT NULL, project_id VARCHAR(64) NOT NULL, kind VARCHAR(64) NOT NULL, object_id VARCHAR(64) NOT NULL,
 version INTEGER NOT NULL, selected_ref_json TEXT NOT NULL, PRIMARY KEY(workspace_id,project_id,kind,object_id)
);
CREATE TABLE mate_bidding_revision (
 id VARCHAR(64) PRIMARY KEY, workspace_id VARCHAR(64) NOT NULL, project_id VARCHAR(64) NOT NULL, kind VARCHAR(64) NOT NULL,
 object_id VARCHAR(64) NOT NULL, version INTEGER NOT NULL, payload_json TEXT NOT NULL, input_refs_json TEXT NOT NULL,
 status VARCHAR(32) NOT NULL, digest VARCHAR(64) NOT NULL, created_at TIMESTAMP NOT NULL,
 CONSTRAINT uq_bidding_revision UNIQUE(workspace_id,project_id,kind,object_id,version)
);
CREATE INDEX idx_bidding_revision_project ON mate_bidding_revision(workspace_id,project_id,kind,created_at);
CREATE TABLE mate_bidding_task (
 id VARCHAR(64) PRIMARY KEY, workspace_id VARCHAR(64) NOT NULL, project_id VARCHAR(64) NOT NULL, agent_id VARCHAR(64) NOT NULL,
 skill_package_id VARCHAR(64) NOT NULL, config_digest VARCHAR(64) NOT NULL, input_json TEXT NOT NULL, input_refs_json TEXT NOT NULL,
 status VARCHAR(32) NOT NULL, active_attempt_id VARCHAR(64), deadline_at TIMESTAMP, cycle_no INTEGER DEFAULT 0 NOT NULL,
 cycle_attempt INTEGER DEFAULT 0 NOT NULL, attempt_count INTEGER DEFAULT 0 NOT NULL, next_run_at TIMESTAMP, boot_id VARCHAR(64),
 created_at TIMESTAMP NOT NULL, updated_at TIMESTAMP NOT NULL
);
CREATE INDEX idx_bidding_task_status ON mate_bidding_task(status,next_run_at,workspace_id);
CREATE TABLE mate_bidding_attempt (
 id VARCHAR(64) PRIMARY KEY, workspace_id VARCHAR(64) NOT NULL, project_id VARCHAR(64) NOT NULL, task_id VARCHAR(64) NOT NULL,
 attempt_no INTEGER NOT NULL, token VARCHAR(128) NOT NULL UNIQUE, state VARCHAR(32) NOT NULL, output_json TEXT,
 tool_receipts_json TEXT NOT NULL, rejected_output TEXT, error_json TEXT, started_at TIMESTAMP, finished_at TIMESTAMP,
 CONSTRAINT uq_bidding_attempt UNIQUE(task_id,attempt_no)
);
CREATE TABLE mate_bidding_decision (
 id VARCHAR(64) PRIMARY KEY, workspace_id VARCHAR(64) NOT NULL, project_id VARCHAR(64) NOT NULL, target_ref_json TEXT NOT NULL,
 decision VARCHAR(32) NOT NULL, reason TEXT, actor_id VARCHAR(64) NOT NULL, created_at TIMESTAMP NOT NULL
);
CREATE TABLE mate_bidding_operation (
 workspace_id VARCHAR(64) NOT NULL, actor_id VARCHAR(64) NOT NULL, operation_id VARCHAR(128) NOT NULL,
 request_digest VARCHAR(64) NOT NULL, result_json TEXT NOT NULL, created_at TIMESTAMP NOT NULL,
 PRIMARY KEY(workspace_id,actor_id,operation_id)
);
CREATE TABLE mate_bidding_skill_package (
 id VARCHAR(64) PRIMARY KEY, workspace_id VARCHAR(64) NOT NULL, project_id VARCHAR(64) NOT NULL, skill_id VARCHAR(128) NOT NULL,
 version VARCHAR(128) NOT NULL, digest VARCHAR(64) NOT NULL, files_json TEXT NOT NULL, created_at TIMESTAMP NOT NULL
);
