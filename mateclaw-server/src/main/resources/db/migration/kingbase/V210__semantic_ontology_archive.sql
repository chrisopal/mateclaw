ALTER TABLE mate_semantic_ontology ADD COLUMN archived BOOLEAN NOT NULL DEFAULT FALSE;

-- Whole-ontology lifecycle events have no individual revision.
ALTER TABLE mate_semantic_governance_record ALTER COLUMN revision_id DROP NOT NULL;
