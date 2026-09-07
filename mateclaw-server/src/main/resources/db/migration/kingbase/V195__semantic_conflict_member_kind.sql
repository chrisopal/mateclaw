ALTER TABLE mate_semantic_conflict
 ADD COLUMN left_member_kind VARCHAR(24) DEFAULT 'STATEMENT' NOT NULL;
ALTER TABLE mate_semantic_conflict
 ADD COLUMN right_member_kind VARCHAR(24) DEFAULT 'STATEMENT' NOT NULL;
