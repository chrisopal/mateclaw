ALTER TABLE mate_bidding_source ADD COLUMN filename VARCHAR(512) DEFAULT '' NOT NULL;
ALTER TABLE mate_bidding_source ADD COLUMN read_status VARCHAR(32) DEFAULT 'PENDING' NOT NULL;
ALTER TABLE mate_bidding_source ADD COLUMN problems_json CLOB DEFAULT '[]' NOT NULL;
ALTER TABLE mate_bidding_source ADD COLUMN read_completed_at TIMESTAMP;
