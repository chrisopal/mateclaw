CREATE TABLE mate_semantic_source_snapshot (
 id VARCHAR(36) PRIMARY KEY, graph_id VARCHAR(36) NOT NULL, source_kind VARCHAR(24) NOT NULL,
 source_id VARCHAR(64) NOT NULL, source_title VARCHAR(256) NOT NULL, capture_version BIGINT NOT NULL,
 text_digest VARCHAR(64) NOT NULL, text_content LONGTEXT NOT NULL, created_by VARCHAR(32) NOT NULL,
 created_at DATETIME(6) NOT NULL, UNIQUE KEY uq_sem_snapshot_source_version(graph_id,source_kind,source_id,capture_version),
 KEY idx_sem_snapshot_graph(graph_id,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE mate_semantic_import_job (
 id VARCHAR(36) PRIMARY KEY, graph_id VARCHAR(36) NOT NULL, source_kind VARCHAR(24) NOT NULL,
 source_id VARCHAR(64) NOT NULL, operation_id VARCHAR(128) NOT NULL, request_hash VARCHAR(64) NOT NULL,
 status VARCHAR(16) NOT NULL, snapshot_id VARCHAR(36), attempts INT NOT NULL, error_message VARCHAR(512),
 lease_owner VARCHAR(64), lease_until DATETIME(6), retry_of_job_id VARCHAR(36),
 created_by VARCHAR(32) NOT NULL, created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL,
 UNIQUE KEY uq_sem_import_operation(graph_id,operation_id), KEY idx_sem_import_status_lease(status,lease_until)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE mate_semantic_evidence (
 id VARCHAR(36) PRIMARY KEY, graph_id VARCHAR(36) NOT NULL, snapshot_id VARCHAR(36) NOT NULL,
 operation_id VARCHAR(128) NOT NULL, start_codepoint INT NOT NULL, end_codepoint INT NOT NULL,
 exact_quote LONGTEXT NOT NULL, created_by VARCHAR(32) NOT NULL, created_at DATETIME(6) NOT NULL,
 UNIQUE KEY uq_sem_evidence_operation(graph_id,operation_id), KEY idx_sem_evidence_snapshot(snapshot_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE mate_semantic_statement (
 id VARCHAR(36) PRIMARY KEY, graph_id VARCHAR(36) NOT NULL, current_revision INT NOT NULL,
 created_by VARCHAR(32) NOT NULL, created_at DATETIME(6) NOT NULL, KEY idx_sem_statement_graph(graph_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE mate_semantic_statement_revision (
 statement_id VARCHAR(36) NOT NULL, revision INT NOT NULL, graph_id VARCHAR(36) NOT NULL,
 ontology_revision_id VARCHAR(36) NOT NULL, subject_id VARCHAR(36) NOT NULL,
 predicate_kind VARCHAR(16) NOT NULL, predicate_key VARCHAR(128) NOT NULL, review_status VARCHAR(16) NOT NULL,
 validity_kind VARCHAR(16) NOT NULL, valid_from DATETIME(6), valid_to DATETIME(6), value_type VARCHAR(16) NOT NULL,
 value_text LONGTEXT, unit VARCHAR(32), target_entity_id VARCHAR(36), content_json LONGTEXT NOT NULL,
 actor_id VARCHAR(32) NOT NULL, reason VARCHAR(1000), created_at DATETIME(6) NOT NULL,
 PRIMARY KEY(statement_id,revision), KEY idx_sem_revision_graph_review(graph_id,review_status,subject_id,predicate_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE mate_semantic_revision_evidence (statement_id VARCHAR(36) NOT NULL, revision INT NOT NULL, evidence_id VARCHAR(36) NOT NULL, PRIMARY KEY(statement_id,revision,evidence_id)) ENGINE=InnoDB;
CREATE TABLE mate_semantic_change_proposal (id VARCHAR(36) PRIMARY KEY, graph_id VARCHAR(36) NOT NULL, target_statement_id VARCHAR(36) NOT NULL, expected_revision INT NOT NULL, operation_id VARCHAR(128) NOT NULL, payload_json LONGTEXT NOT NULL, status VARCHAR(16) NOT NULL, result_revision INT, proposed_by VARCHAR(32) NOT NULL, created_at DATETIME(6) NOT NULL, UNIQUE KEY uq_sem_change_operation(graph_id,operation_id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE mate_semantic_conflict (id VARCHAR(36) PRIMARY KEY, graph_id VARCHAR(36) NOT NULL, kind VARCHAR(40) NOT NULL, status VARCHAR(16) NOT NULL, left_statement_id VARCHAR(36) NOT NULL, left_revision INT NOT NULL, right_statement_id VARCHAR(36) NOT NULL, right_revision INT NOT NULL, resolution_json LONGTEXT, resolved_by VARCHAR(32), resolved_at DATETIME(6), created_at DATETIME(6) NOT NULL, KEY idx_sem_conflict_graph_status(graph_id,status)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE mate_semantic_source_governance (graph_id VARCHAR(36) NOT NULL, source_kind VARCHAR(24) NOT NULL, source_id VARCHAR(64) NOT NULL, state VARCHAR(16) NOT NULL, actor_id VARCHAR(32) NOT NULL, reason VARCHAR(1000) NOT NULL, created_at DATETIME(6) NOT NULL, PRIMARY KEY(graph_id,source_kind,source_id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE mate_semantic_snapshot_exclusion (graph_id VARCHAR(36) NOT NULL, snapshot_id VARCHAR(36) NOT NULL, actor_id VARCHAR(32) NOT NULL, reason VARCHAR(1000) NOT NULL, created_at DATETIME(6) NOT NULL, PRIMARY KEY(graph_id,snapshot_id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE mate_semantic_mutation_command (id VARCHAR(36) PRIMARY KEY, graph_id VARCHAR(36) NOT NULL, operation_id VARCHAR(128) NOT NULL, kind VARCHAR(40) NOT NULL, payload_hash VARCHAR(64) NOT NULL, result_json LONGTEXT NOT NULL, created_at DATETIME(6) NOT NULL, UNIQUE KEY uq_sem_mutation_operation(graph_id,operation_id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
