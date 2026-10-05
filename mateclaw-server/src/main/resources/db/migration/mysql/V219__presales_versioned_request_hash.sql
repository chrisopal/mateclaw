-- Preserve legacy receipts; reserve the v2: prefix for lossless request hashes.
ALTER TABLE mate_presales_operation MODIFY COLUMN request_hash VARCHAR(67) NOT NULL;
