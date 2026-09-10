-- Derived query columns for standard assertions; content_json remains the revision payload.
ALTER TABLE mate_semantic_statement_revision ADD COLUMN predicate_iri VARCHAR(2048);
ALTER TABLE mate_semantic_statement_revision ADD COLUMN assertion_kind VARCHAR(40);
ALTER TABLE mate_semantic_statement_revision ADD COLUMN assertion_text LONGTEXT;
ALTER TABLE mate_semantic_statement_revision MODIFY COLUMN predicate_key VARCHAR(128) NULL;
