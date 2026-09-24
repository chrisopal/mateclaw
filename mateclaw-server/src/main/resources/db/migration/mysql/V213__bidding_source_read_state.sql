ALTER TABLE mate_bidding_source ADD COLUMN filename VARCHAR(512) NOT NULL DEFAULT '';
ALTER TABLE mate_bidding_source ADD COLUMN read_status VARCHAR(32) NOT NULL DEFAULT 'PENDING';
ALTER TABLE mate_bidding_source ADD COLUMN problems_json LONGTEXT NULL;
ALTER TABLE mate_bidding_source ADD COLUMN read_completed_at TIMESTAMP NULL;
UPDATE mate_bidding_source SET problems_json='[]' WHERE problems_json IS NULL;
ALTER TABLE mate_bidding_source MODIFY COLUMN problems_json LONGTEXT NOT NULL;
