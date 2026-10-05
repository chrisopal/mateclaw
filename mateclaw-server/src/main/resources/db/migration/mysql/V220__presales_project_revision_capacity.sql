-- Stop all old application writers before applying this migration.
-- Keep old JSON, receipts and artifacts unchanged; do not rerun V218 on widened versions.
ALTER TABLE mate_presales_project MODIFY COLUMN version BIGINT NOT NULL;
ALTER TABLE mate_presales_project MODIFY COLUMN listing_project_version BIGINT NULL;
ALTER TABLE mate_presales_revision MODIFY COLUMN version BIGINT NOT NULL;
