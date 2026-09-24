CREATE TABLE mate_bidding_project (
 id VARCHAR(64) PRIMARY KEY, workspace_id VARCHAR(64) NOT NULL, project_id VARCHAR(64) NOT NULL,
 owner_id VARCHAR(64) NOT NULL, version INT NOT NULL, name VARCHAR(200) NOT NULL, lot_name VARCHAR(200) NOT NULL,
 stage VARCHAR(32) NOT NULL, body_json LONGTEXT NOT NULL, created_at TIMESTAMP NOT NULL, updated_at TIMESTAMP NOT NULL,
 KEY idx_bidding_project_workspace(workspace_id,updated_at)
);
CREATE TABLE mate_bidding_source (
 id VARCHAR(64) PRIMARY KEY, workspace_id VARCHAR(64) NOT NULL, project_id VARCHAR(64) NOT NULL,
 source_id VARCHAR(64) NOT NULL, version INT NOT NULL, kind VARCHAR(64) NOT NULL, digest VARCHAR(64) NOT NULL,
 content LONGBLOB NOT NULL, blocks_json LONGTEXT NOT NULL, quality VARCHAR(32) NOT NULL, read_token VARCHAR(128), read_started_at TIMESTAMP NULL,
 created_at TIMESTAMP NOT NULL, UNIQUE KEY uq_bidding_source(project_id,source_id,version)
);
CREATE TABLE mate_bidding_head (
 workspace_id VARCHAR(64) NOT NULL, project_id VARCHAR(64) NOT NULL, kind VARCHAR(64) NOT NULL, object_id VARCHAR(64) NOT NULL,
 version INT NOT NULL, selected_ref_json LONGTEXT NOT NULL, PRIMARY KEY(workspace_id,project_id,kind,object_id)
);
CREATE TABLE mate_bidding_revision (
 id VARCHAR(64) PRIMARY KEY, workspace_id VARCHAR(64) NOT NULL, project_id VARCHAR(64) NOT NULL, kind VARCHAR(64) NOT NULL,
 object_id VARCHAR(64) NOT NULL, version INT NOT NULL, payload_json LONGTEXT NOT NULL, input_refs_json LONGTEXT NOT NULL,
 status VARCHAR(32) NOT NULL, digest VARCHAR(64) NOT NULL, created_at TIMESTAMP NOT NULL,
 UNIQUE KEY uq_bidding_revision(workspace_id,project_id,kind,object_id,version), KEY idx_bidding_revision_project(workspace_id,project_id,kind,created_at)
);
CREATE TABLE mate_bidding_task (
 id VARCHAR(64) PRIMARY KEY, workspace_id VARCHAR(64) NOT NULL, project_id VARCHAR(64) NOT NULL, agent_id VARCHAR(64) NOT NULL,
 skill_package_id VARCHAR(64) NOT NULL, config_digest VARCHAR(64) NOT NULL, input_json LONGTEXT NOT NULL, input_refs_json LONGTEXT NOT NULL,
 status VARCHAR(32) NOT NULL, active_attempt_id VARCHAR(64), deadline_at TIMESTAMP NULL, cycle_no INT NOT NULL DEFAULT 0,
 cycle_attempt INT NOT NULL DEFAULT 0, attempt_count INT NOT NULL DEFAULT 0, next_run_at TIMESTAMP NULL, boot_id VARCHAR(64),
 created_at TIMESTAMP NOT NULL, updated_at TIMESTAMP NOT NULL, KEY idx_bidding_task_status(status,next_run_at,workspace_id)
);
CREATE TABLE mate_bidding_attempt (
 id VARCHAR(64) PRIMARY KEY, workspace_id VARCHAR(64) NOT NULL, project_id VARCHAR(64) NOT NULL, task_id VARCHAR(64) NOT NULL,
 attempt_no INT NOT NULL, token VARCHAR(128) NOT NULL UNIQUE, state VARCHAR(32) NOT NULL, output_json LONGTEXT,
 tool_receipts_json LONGTEXT NOT NULL, rejected_output LONGTEXT, error_json LONGTEXT, started_at TIMESTAMP NULL, finished_at TIMESTAMP NULL,
 UNIQUE KEY uq_bidding_attempt(task_id,attempt_no)
);
CREATE TABLE mate_bidding_decision (
 id VARCHAR(64) PRIMARY KEY, workspace_id VARCHAR(64) NOT NULL, project_id VARCHAR(64) NOT NULL, target_ref_json LONGTEXT NOT NULL,
 decision VARCHAR(32) NOT NULL, reason LONGTEXT, actor_id VARCHAR(64) NOT NULL, created_at TIMESTAMP NOT NULL
);
CREATE TABLE mate_bidding_operation (
 workspace_id VARCHAR(64) NOT NULL, actor_id VARCHAR(64) NOT NULL, operation_id VARCHAR(128) NOT NULL,
 request_digest VARCHAR(64) NOT NULL, result_json LONGTEXT NOT NULL, created_at TIMESTAMP NOT NULL,
 PRIMARY KEY(workspace_id,actor_id,operation_id)
);
CREATE TABLE mate_bidding_skill_package (
 id VARCHAR(64) PRIMARY KEY, workspace_id VARCHAR(64) NOT NULL, project_id VARCHAR(64) NOT NULL, skill_id VARCHAR(128) NOT NULL,
 version VARCHAR(128) NOT NULL, digest VARCHAR(64) NOT NULL, files_json LONGTEXT NOT NULL, created_at TIMESTAMP NOT NULL
);
