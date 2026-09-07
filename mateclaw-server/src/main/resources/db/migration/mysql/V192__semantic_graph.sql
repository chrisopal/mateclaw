CREATE TABLE mate_semantic_graph (
 id VARCHAR(36) PRIMARY KEY, workspace_id BIGINT NOT NULL, kb_id BIGINT NOT NULL,
 ontology_revision_id VARCHAR(36) NOT NULL, enabled BOOLEAN NOT NULL,
 mutation_version BIGINT NOT NULL DEFAULT 0, created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL,
 UNIQUE KEY uq_sem_graph_kb(workspace_id,kb_id), KEY idx_sem_graph_revision(ontology_revision_id),
 CONSTRAINT fk_sem_graph_revision FOREIGN KEY(ontology_revision_id) REFERENCES mate_semantic_ontology_revision(id)
) ENGINE=InnoDB;
CREATE TABLE mate_semantic_entity (
 id VARCHAR(36) PRIMARY KEY, graph_id VARCHAR(36) NOT NULL, type_key VARCHAR(128) NOT NULL,
 display_name VARCHAR(256) NOT NULL, status VARCHAR(16) NOT NULL, created_by VARCHAR(32) NOT NULL,
 created_at DATETIME(6) NOT NULL, KEY idx_sem_entity_graph_type(graph_id,type_key),
 CONSTRAINT fk_sem_entity_graph FOREIGN KEY(graph_id) REFERENCES mate_semantic_graph(id)
) ENGINE=InnoDB;
