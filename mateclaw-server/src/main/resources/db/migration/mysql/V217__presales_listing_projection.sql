-- Derived listing facts only; V218 backfills before project queries can use them.
-- Existing body, metadata, revisions, receipts and frozen artifacts remain authoritative.
ALTER TABLE mate_presales_project ADD COLUMN listing_contract INTEGER;
ALTER TABLE mate_presales_project ADD COLUMN listing_project_version INTEGER;
ALTER TABLE mate_presales_project ADD COLUMN listing_name_key LONGTEXT;
ALTER TABLE mate_presales_project ADD COLUMN listing_customer_key LONGTEXT;
ALTER TABLE mate_presales_project ADD COLUMN listing_status_key LONGTEXT;
ALTER TABLE mate_presales_project ADD COLUMN listing_owner_key LONGTEXT;
ALTER TABLE mate_presales_project ADD COLUMN listing_stage_key LONGTEXT;
ALTER TABLE mate_presales_project ADD COLUMN listing_summary_json LONGTEXT;
ALTER TABLE mate_presales_project ADD COLUMN listing_decode_failure VARCHAR(64);
ALTER TABLE mate_presales_project ADD COLUMN listing_stage_failure VARCHAR(64);
ALTER TABLE mate_presales_project ADD COLUMN listing_summary_failure VARCHAR(64);
CREATE INDEX idx_presales_listing_order ON mate_presales_project(workspace_id,name,id);
