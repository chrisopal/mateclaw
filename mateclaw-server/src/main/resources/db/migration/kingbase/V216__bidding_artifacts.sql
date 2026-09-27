CREATE TABLE mate_bidding_artifact (
 workspace_id VARCHAR(64) NOT NULL, project_id VARCHAR(64) NOT NULL, id VARCHAR(64) NOT NULL,
 manuscript_ref_json TEXT NOT NULL, template_ref_json TEXT NOT NULL, format_ref_json TEXT NOT NULL,
 mode VARCHAR(16) NOT NULL, format VARCHAR(16) NOT NULL, digest VARCHAR(64) NOT NULL,
 byte_size BIGINT NOT NULL, content BYTEA NOT NULL, checks_json TEXT NOT NULL,
 generator_attempt_id VARCHAR(64) NOT NULL UNIQUE, status VARCHAR(32) NOT NULL, decision_id VARCHAR(64), created_at TIMESTAMP NOT NULL,
 PRIMARY KEY(workspace_id,project_id,id)
);
CREATE INDEX idx_bidding_artifact_project ON mate_bidding_artifact(workspace_id,project_id,created_at);
