-- New V2 projects only. Current pointers reference immutable object revisions.
CREATE TABLE mate_presales_object (
 workspace_id VARCHAR(64) NOT NULL, project_id VARCHAR(64) NOT NULL,
 object_kind VARCHAR(32) NOT NULL, parent_id VARCHAR(64) NOT NULL,
 object_id VARCHAR(128) NOT NULL, storage_revision BIGINT NOT NULL,
 PRIMARY KEY(workspace_id,project_id,object_kind,parent_id,object_id)
);
CREATE TABLE mate_presales_object_revision (
 workspace_id VARCHAR(64) NOT NULL, project_id VARCHAR(64) NOT NULL,
 object_kind VARCHAR(32) NOT NULL, parent_id VARCHAR(64) NOT NULL,
 object_id VARCHAR(128) NOT NULL, storage_revision BIGINT NOT NULL, body_json TEXT NOT NULL,
 PRIMARY KEY(workspace_id,project_id,object_kind,parent_id,object_id,storage_revision)
);
