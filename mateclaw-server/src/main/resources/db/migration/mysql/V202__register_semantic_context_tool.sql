-- Explicit opt-in; preserve existing tool choices and agent bindings.
INSERT INTO mate_tool (id, name, display_name, description, tool_type, bean_name, icon, enabled, builtin, create_time, update_time, deleted)
VALUES (1000000202, 'SemanticContextTool', 'OWL domain context', 'Read pinned ontology axioms, time-valid accepted facts and authorized source snapshots with bounded context.', 'builtin', 'semanticContextTool', 'search', FALSE, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0);
