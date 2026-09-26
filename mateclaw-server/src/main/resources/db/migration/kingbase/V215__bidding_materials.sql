CREATE TABLE mate_bidding_handoff (
 id VARCHAR(64) PRIMARY KEY, workspace_id VARCHAR(64) NOT NULL, project_id VARCHAR(64) NOT NULL,
 presales_project_id VARCHAR(64) NOT NULL, release_id VARCHAR(64) NOT NULL, baseline_ref VARCHAR(128) NOT NULL,
 solution_ref VARCHAR(128) NOT NULL, snapshot_json TEXT NOT NULL, digest VARCHAR(64) NOT NULL,
 actor_id VARCHAR(64) NOT NULL, received_at TIMESTAMP NOT NULL
);
CREATE INDEX idx_bidding_handoff_project ON mate_bidding_handoff(workspace_id,project_id,received_at);
CREATE TABLE mate_bidding_material (
 id VARCHAR(64) PRIMARY KEY, workspace_id VARCHAR(64) NOT NULL, project_id VARCHAR(64) NOT NULL,
 source_kind VARCHAR(64) NOT NULL, external_id VARCHAR(128) NOT NULL, version INTEGER NOT NULL,
 digest VARCHAR(64) NOT NULL, content_json TEXT NOT NULL, access_ref_json TEXT NOT NULL,
 validity VARCHAR(32) NOT NULL, created_at TIMESTAMP NOT NULL,
 CONSTRAINT uq_bidding_material_ref UNIQUE(workspace_id,project_id,external_id,version,digest)
);
CREATE INDEX idx_bidding_material_project ON mate_bidding_material(workspace_id,project_id,validity);
