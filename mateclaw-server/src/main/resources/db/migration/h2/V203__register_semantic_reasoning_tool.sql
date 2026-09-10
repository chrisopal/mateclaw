-- Explicit opt-in; reasoning is read-only but launches a bounded child process.
MERGE INTO mate_tool (id, name, display_name, description, tool_type, bean_name, icon, enabled, builtin, create_time, update_time, deleted)
KEY (id) VALUES (1000000203, 'SemanticReasoningTool', 'OWL reasoning', 'Run bounded read-only OWL reasoning against a pinned ontology and an explicitly selected assertion scope.', 'builtin', 'semanticReasoningTool', 'search', FALSE, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0);
