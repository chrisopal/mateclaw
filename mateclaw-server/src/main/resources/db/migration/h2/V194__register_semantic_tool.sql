-- Deliberately disabled: enable the module and explicitly opt into the tool separately.
INSERT INTO mate_tool (id, name, display_name, description, tool_type, bean_name, icon, enabled, builtin, create_time, update_time, deleted)
VALUES (1000000194, 'SemanticTool', 'Semantic evidence search', 'Read accepted and supported facts with evidence references in the authenticated workspace.', 'builtin', 'semanticTool', 'search', FALSE, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0);
