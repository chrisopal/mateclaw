ALTER TABLE mate_bidding_source ADD COLUMN filename VARCHAR(512) NOT NULL DEFAULT '';
ALTER TABLE mate_bidding_source ADD COLUMN read_status VARCHAR(32) NOT NULL DEFAULT 'PENDING';
ALTER TABLE mate_bidding_source ADD COLUMN problems_json TEXT NOT NULL DEFAULT '[]';
ALTER TABLE mate_bidding_source ADD COLUMN read_completed_at TIMESTAMP;
