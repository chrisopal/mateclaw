-- M1 ontology authoring. Draft writes lock the parent and compare draft_version.
CREATE TABLE mate_semantic_ontology (
 id VARCHAR(36) PRIMARY KEY, workspace_id BIGINT NOT NULL,
 name VARCHAR(128) NOT NULL, description VARCHAR(1000) NOT NULL,
 latest_version INTEGER, latest_revision_id VARCHAR(36), draft_id VARCHAR(36), draft_counter BIGINT NOT NULL DEFAULT 0,
 updated_at TIMESTAMP(6) NOT NULL
);
CREATE INDEX idx_sem_ontology_workspace ON mate_semantic_ontology(workspace_id,updated_at);
CREATE TABLE mate_semantic_ontology_revision (
 id VARCHAR(36) PRIMARY KEY, ontology_id VARCHAR(36) NOT NULL,
 version INTEGER NOT NULL, draft_version BIGINT NOT NULL,
 revision_state VARCHAR(16) NOT NULL, draft_slot INTEGER,
 name VARCHAR(128) NOT NULL, description VARCHAR(1000) NOT NULL,
 definition_json LONGTEXT NOT NULL, base_revision_id VARCHAR(36),
 available_for_new_bindings BOOLEAN NOT NULL,
 published_at TIMESTAMP(6), published_by VARCHAR(32), publication_note VARCHAR(1000),
 CONSTRAINT fk_sem_revision_ontology FOREIGN KEY (ontology_id) REFERENCES mate_semantic_ontology(id),
 CONSTRAINT uq_sem_revision_version UNIQUE (ontology_id,version),
 CONSTRAINT uq_sem_active_draft UNIQUE (ontology_id,draft_slot),
 CONSTRAINT ck_sem_revision_state CHECK ((revision_state='DRAFT' AND draft_slot IS NOT NULL AND draft_slot=1) OR (revision_state='PUBLISHED' AND draft_slot IS NULL))
);
CREATE TABLE mate_semantic_command_record (
 id VARCHAR(36) PRIMARY KEY, workspace_id BIGINT NOT NULL,
 operation_id VARCHAR(128) NOT NULL, kind VARCHAR(40) NOT NULL,
 resource_id VARCHAR(36) NOT NULL, payload_hash VARCHAR(64) NOT NULL,
 result_json LONGTEXT NOT NULL, created_at TIMESTAMP(6) NOT NULL,
 CONSTRAINT uq_sem_command_scope UNIQUE (workspace_id,operation_id)
);
CREATE TABLE mate_semantic_governance_record (
 id VARCHAR(36) PRIMARY KEY, workspace_id BIGINT NOT NULL,
 ontology_id VARCHAR(36) NOT NULL, revision_id VARCHAR(36) NOT NULL,
 action VARCHAR(40) NOT NULL, actor_id VARCHAR(32) NOT NULL,
 detail_json LONGTEXT NOT NULL, created_at TIMESTAMP(6) NOT NULL
);
CREATE INDEX idx_sem_governance_ontology ON mate_semantic_governance_record(workspace_id,ontology_id);
