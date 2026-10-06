-- Stop all old application writers before applying this migration.
-- Keep old JSON, receipts and artifacts unchanged; do not rerun V218 on widened versions.
ALTER TABLE mate_presales_project ALTER COLUMN version TYPE BIGINT;
ALTER TABLE mate_presales_project ALTER COLUMN listing_project_version TYPE BIGINT;
ALTER TABLE mate_presales_revision ALTER COLUMN version TYPE BIGINT;
