-- Preserve legacy receipts; reserve the v2: prefix for lossless request hashes.
ALTER TABLE mate_presales_operation ALTER COLUMN request_hash TYPE VARCHAR(67);
