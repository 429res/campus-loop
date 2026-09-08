ALTER TABLE cl_item DROP CONSTRAINT ck_item_status;
ALTER TABLE cl_item ADD CONSTRAINT ck_item_status CHECK(status IN ('DRAFT','PENDING_REVIEW','REJECTED','AVAILABLE','RESERVED','EXCHANGED','HIDDEN'));
ALTER TABLE cl_item ALTER COLUMN status SET DEFAULT 'PENDING_REVIEW';
ALTER TABLE cl_item ADD COLUMN review_basis VARCHAR(20) NOT NULL DEFAULT 'UNREVIEWED';
ALTER TABLE cl_item ADD CONSTRAINT ck_item_review_basis CHECK(review_basis IN ('UNREVIEWED','LEGACY_DIRECT','ADMIN_REVIEW'));
-- Preserve existing public/exchange lifecycle and versions; this is not an approval.
UPDATE cl_item SET review_basis='LEGACY_DIRECT' WHERE status IN ('AVAILABLE','RESERVED','EXCHANGED');

CREATE TABLE cl_item_review_audit (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 item_id BIGINT NOT NULL,
 operator_user_id BIGINT NOT NULL,
 operator_display_name VARCHAR(64) NOT NULL,
 action VARCHAR(16) NOT NULL,
 reason VARCHAR(1000) NULL,
 previous_status VARCHAR(20) NULL,
 new_status VARCHAR(20) NOT NULL,
 previous_version INT NULL,
 new_version INT NOT NULL,
 previous_snapshot_json TEXT NULL,
 new_snapshot_json TEXT NOT NULL,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 CONSTRAINT fk_review_item FOREIGN KEY(item_id) REFERENCES cl_item(id) ON DELETE RESTRICT,
 CONSTRAINT fk_review_operator FOREIGN KEY(operator_user_id) REFERENCES cl_user(id) ON DELETE RESTRICT,
 CONSTRAINT uk_review_item_version UNIQUE(item_id,new_version),
 CONSTRAINT ck_review_reason CHECK(action NOT IN ('APPROVE','REJECT') OR (reason IS NOT NULL AND CHAR_LENGTH(TRIM(reason))>0)),
 CONSTRAINT ck_review_action CHECK(action IN ('SUBMIT','APPROVE','REJECT','WITHDRAW')),
 CONSTRAINT ck_review_version CHECK((previous_version IS NULL AND new_version=0) OR (previous_version IS NOT NULL AND previous_version>=0 AND new_version=previous_version+1))
);
CREATE INDEX idx_review_item_created ON cl_item_review_audit(item_id,created_at,id);
