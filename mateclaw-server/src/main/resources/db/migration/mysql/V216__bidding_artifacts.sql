CREATE TABLE mate_bidding_artifact (
 workspace_id VARCHAR(64) NOT NULL, project_id VARCHAR(64) NOT NULL, id VARCHAR(64) NOT NULL,
 manuscript_ref_json LONGTEXT NOT NULL, template_ref_json LONGTEXT NOT NULL, format_ref_json LONGTEXT NOT NULL,
 mode VARCHAR(16) NOT NULL, format VARCHAR(16) NOT NULL, digest VARCHAR(64) NOT NULL,
 byte_size BIGINT NOT NULL, content LONGBLOB NOT NULL, checks_json LONGTEXT NOT NULL,
 generator_attempt_id VARCHAR(64) NOT NULL, status VARCHAR(32) NOT NULL, decision_id VARCHAR(64), created_at TIMESTAMP NOT NULL,
 PRIMARY KEY(workspace_id,project_id,id), UNIQUE KEY uq_bidding_artifact_attempt(generator_attempt_id),
 KEY idx_bidding_artifact_project(workspace_id,project_id,created_at)
);
