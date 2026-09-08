-- Separate state guard and append-only decision. No backfill of verification.
CREATE TABLE cl_history_verification_state (
 history_id BIGINT PRIMARY KEY,
 version INT NOT NULL DEFAULT 0,
 status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
 CONSTRAINT fk_verification_history FOREIGN KEY(history_id) REFERENCES cl_item_history(id) ON DELETE RESTRICT,
 CONSTRAINT uq_verification_version UNIQUE(history_id,version),
 CONSTRAINT ck_verification_state CHECK((status='PENDING' AND version=0) OR (status IN ('APPROVED','REJECTED') AND version=1))
);
CREATE TABLE cl_history_verification_audit (
 history_id BIGINT PRIMARY KEY,
 version INT NOT NULL,
 admin_id BIGINT NOT NULL,
 idempotency_key VARCHAR(64) NOT NULL,
 request_json TEXT NOT NULL,
 decision VARCHAR(16) NOT NULL,
 scope VARCHAR(500) NOT NULL,
 reason VARCHAR(2000) NOT NULL,
 snapshot_hash CHAR(64) NOT NULL,
 snapshot_json TEXT NOT NULL,
 decided_at TIMESTAMP NOT NULL,
 CONSTRAINT uq_verification_key UNIQUE(admin_id,idempotency_key),
 CONSTRAINT fk_verification_state FOREIGN KEY(history_id,version) REFERENCES cl_history_verification_state(history_id,version) ON DELETE RESTRICT,
 CONSTRAINT fk_verification_admin FOREIGN KEY(admin_id) REFERENCES cl_user(id) ON DELETE RESTRICT,
 CONSTRAINT ck_verification_decision CHECK(decision IN ('APPROVED','REJECTED') AND version=1)
);
