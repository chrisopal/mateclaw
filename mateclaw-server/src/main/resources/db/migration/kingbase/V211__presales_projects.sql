CREATE TABLE mate_presales_project (
 id VARCHAR(64) PRIMARY KEY, workspace_id VARCHAR(64) NOT NULL, version INTEGER NOT NULL,
 name VARCHAR(300) NOT NULL, status VARCHAR(32) NOT NULL, body_json TEXT NOT NULL
);
CREATE INDEX idx_presales_workspace ON mate_presales_project(workspace_id);
CREATE TABLE mate_presales_operation (
 workspace_id VARCHAR(64) NOT NULL, actor_id VARCHAR(64) NOT NULL, operation_id VARCHAR(128) NOT NULL,
 request_hash VARCHAR(64) NOT NULL, response_json TEXT NOT NULL,
 PRIMARY KEY(workspace_id, actor_id, operation_id)
);
CREATE TABLE mate_presales_revision (
 project_id VARCHAR(64) NOT NULL, version INTEGER NOT NULL, actor_id VARCHAR(64) NOT NULL,
 action VARCHAR(64) NOT NULL, body_json TEXT NOT NULL, created_at TIMESTAMP NOT NULL,
 PRIMARY KEY(project_id,version)
);
CREATE TABLE mate_presales_artifact (
 project_id VARCHAR(64) NOT NULL, release_id VARCHAR(64) NOT NULL, filename VARCHAR(64) NOT NULL,
 digest VARCHAR(64) NOT NULL, content_base64 TEXT NOT NULL,
 PRIMARY KEY(project_id,release_id,filename)
);
