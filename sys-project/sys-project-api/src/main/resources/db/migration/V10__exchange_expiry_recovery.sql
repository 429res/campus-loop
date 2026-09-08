-- Operational retry state only; preserve business status, version, original deadline and all holds.
ALTER TABLE cl_exchange ADD COLUMN expiry_retry_at TIMESTAMP NULL;
ALTER TABLE cl_exchange ADD COLUMN expiry_retry_count INT NOT NULL DEFAULT 0;
ALTER TABLE cl_exchange ADD COLUMN expiry_failure_code VARCHAR(32) NULL;
ALTER TABLE cl_exchange ADD CONSTRAINT ck_exchange_expiry_retry_count CHECK(expiry_retry_count >= 0);
CREATE INDEX idx_exchange_expiry_scan ON cl_exchange(status,expires_at,id);
