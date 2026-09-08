-- Register the ontology authoring tool used by the workspace employee preset.
MERGE INTO mate_tool (id, name, display_name, description, tool_type, bean_name, icon, enabled, builtin, create_time, update_time, deleted)
KEY (id)
VALUES (1000000198, 'OntologyAuthoringTool', 'Ontology authoring', 'Create, validate and save ontology drafts from authorized workspace sources; publication always remains a human UI action.', 'builtin', 'ontologyAuthoringTool', '🧭', TRUE, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0);
