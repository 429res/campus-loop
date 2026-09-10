ALTER TABLE cl_user ADD COLUMN system_account BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE cl_user ADD CONSTRAINT ck_system_account_no_password CHECK(system_account=FALSE OR password_hash IS NULL);
INSERT INTO cl_user(username,display_name,role,status,admin_permissions,system_account,legacy_exchange_access)
VALUES ('__campus_loop_ai_review__','千问自动审核','ADMIN','ACTIVE','ITEMS,REPORTS,COMMUNITY',TRUE,FALSE);
ALTER TABLE cl_item DROP CONSTRAINT ck_item_review_basis;
ALTER TABLE cl_item ADD CONSTRAINT ck_item_review_basis CHECK(review_basis IN ('UNREVIEWED','LEGACY_DIRECT','ADMIN_REVIEW','AI_REVIEW'));
CREATE TABLE cl_ai_review (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 target_type VARCHAR(20) NOT NULL,
 target_id BIGINT NOT NULL,
 revision VARCHAR(80) NOT NULL,
 state VARCHAR(16) NOT NULL DEFAULT 'READY',
 snapshot_json TEXT NOT NULL,
 snapshot_hash CHAR(64) NOT NULL,
 decision VARCHAR(16) NULL,
 confidence DOUBLE NULL,
 reason VARCHAR(1500) NULL,
 model VARCHAR(100) NULL,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(target_type,target_id,revision),
 CHECK(target_type IN ('ITEM','REPORT','COMMUNITY')),
 CHECK(state IN ('READY','RUNNING','MANUAL','APPLIED','STALE'))
);
CREATE INDEX idx_ai_review_queue ON cl_ai_review(state,id);
