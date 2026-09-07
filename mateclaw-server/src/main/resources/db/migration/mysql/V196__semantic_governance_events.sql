-- Resource-neutral, append-only history for semantic governance mutations.
CREATE TABLE mate_semantic_governance_event (
 id VARCHAR(36) PRIMARY KEY,
 workspace_id BIGINT NOT NULL,
 graph_id VARCHAR(36) NOT NULL,
 resource_kind VARCHAR(32) NOT NULL,
 resource_id VARCHAR(128) NOT NULL,
 resource_version BIGINT,
 action VARCHAR(64) NOT NULL,
 operation_id VARCHAR(128) NOT NULL,
 actor_id VARCHAR(32) NOT NULL,
 reason VARCHAR(1000) NOT NULL,
 result_json LONGTEXT NOT NULL,
 created_at DATETIME(6) NOT NULL,
 KEY idx_sem_governance_event_resource(graph_id,resource_kind,resource_id,created_at),
 KEY idx_sem_governance_event_operation(graph_id,operation_id,created_at)
) ENGINE=InnoDB;
