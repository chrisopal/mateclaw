ALTER TABLE mate_semantic_conflict
 ADD COLUMN left_member_kind VARCHAR(24) NOT NULL DEFAULT 'STATEMENT';
ALTER TABLE mate_semantic_conflict
 ADD COLUMN right_member_kind VARCHAR(24) NOT NULL DEFAULT 'STATEMENT';
