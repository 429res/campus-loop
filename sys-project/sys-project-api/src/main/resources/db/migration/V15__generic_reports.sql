CREATE TABLE cl_report (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 reporter_id BIGINT NOT NULL,
 reporter_display_name VARCHAR(64) NOT NULL,
 target_type VARCHAR(16) NOT NULL,
 target_id BIGINT NOT NULL,
 target_summary VARCHAR(255) NOT NULL,
 reason VARCHAR(1000) NOT NULL,
 idempotency_key VARCHAR(64) NOT NULL,
 request_digest CHAR(64) NOT NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'SUBMITTED',
 version INT NOT NULL DEFAULT 0,
 accepted_by BIGINT NULL,
 accepted_at TIMESTAMP NULL,
 decision VARCHAR(16) NULL,
 decision_reason VARCHAR(1000) NULL,
 decided_by BIGINT NULL,
 decided_at TIMESTAMP NULL,
 created_at TIMESTAMP NOT NULL,
 CONSTRAINT uq_report_idempotency UNIQUE(reporter_id,idempotency_key),
 CONSTRAINT fk_report_reporter FOREIGN KEY(reporter_id) REFERENCES cl_user(id) ON DELETE RESTRICT,
 CONSTRAINT fk_report_target_item FOREIGN KEY(target_id) REFERENCES cl_item(id) ON DELETE RESTRICT,
 CONSTRAINT fk_report_acceptor FOREIGN KEY(accepted_by) REFERENCES cl_user(id) ON DELETE RESTRICT,
 CONSTRAINT fk_report_decider FOREIGN KEY(decided_by) REFERENCES cl_user(id) ON DELETE RESTRICT,
 CONSTRAINT ck_report_target CHECK(target_type='ITEM'),
 CONSTRAINT ck_report_reason CHECK(CHAR_LENGTH(TRIM(reason)) BETWEEN 1 AND 1000),
 CONSTRAINT ck_report_key CHECK(CHAR_LENGTH(idempotency_key) BETWEEN 1 AND 64),
 CONSTRAINT ck_report_digest CHECK(CHAR_LENGTH(request_digest)=64),
 CONSTRAINT ck_report_state CHECK(
   (status='SUBMITTED' AND version=0 AND accepted_by IS NULL AND accepted_at IS NULL AND decision IS NULL AND decision_reason IS NULL AND decided_by IS NULL AND decided_at IS NULL)
   OR (status='IN_REVIEW' AND version>=1 AND accepted_by IS NOT NULL AND accepted_at IS NOT NULL AND decision IS NULL AND decision_reason IS NULL AND decided_by IS NULL AND decided_at IS NULL)
   OR (status='RESOLVED' AND version>=2 AND accepted_by IS NOT NULL AND accepted_at IS NOT NULL AND decision IN ('UPHELD','DISMISSED') AND decision_reason IS NOT NULL AND decided_by IS NOT NULL AND decided_at IS NOT NULL)
 )
);
CREATE INDEX idx_report_queue ON cl_report(status,created_at,id);
CREATE INDEX idx_report_mine ON cl_report(reporter_id,created_at,id);
CREATE INDEX idx_report_target ON cl_report(target_type,target_id);

CREATE TABLE cl_report_evidence (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 report_id BIGINT NOT NULL,
 upload_id VARCHAR(36) NOT NULL,
 position_no INT NOT NULL,
 display_name VARCHAR(128) NOT NULL,
 content_type VARCHAR(64) NOT NULL,
 size_bytes BIGINT NOT NULL,
 content_hash CHAR(64) NOT NULL,
 CONSTRAINT uq_report_evidence_upload UNIQUE(report_id,upload_id),
 CONSTRAINT uq_report_evidence_position UNIQUE(report_id,position_no),
 CONSTRAINT fk_report_evidence_report FOREIGN KEY(report_id) REFERENCES cl_report(id) ON DELETE RESTRICT,
 CONSTRAINT fk_report_evidence_upload FOREIGN KEY(upload_id) REFERENCES cl_upload(id) ON DELETE RESTRICT,
 CONSTRAINT ck_report_evidence_position CHECK(position_no BETWEEN 0 AND 4),
 CONSTRAINT ck_report_evidence_size CHECK(size_bytes BETWEEN 1 AND 5242880),
 CONSTRAINT ck_report_evidence_hash CHECK(CHAR_LENGTH(content_hash)=64)
);
CREATE INDEX idx_report_evidence_upload ON cl_report_evidence(upload_id);

CREATE TABLE cl_report_audit (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 report_id BIGINT NOT NULL,
 actor_user_id BIGINT NOT NULL,
 actor_display_name VARCHAR(64) NOT NULL,
 action VARCHAR(16) NOT NULL,
 previous_status VARCHAR(16) NULL,
 new_status VARCHAR(16) NOT NULL,
 previous_version INT NULL,
 new_version INT NOT NULL,
 reason VARCHAR(1000) NOT NULL,
 decision VARCHAR(16) NULL,
 request_digest CHAR(64) NOT NULL,
 created_at TIMESTAMP NOT NULL,
 CONSTRAINT uq_report_audit_action UNIQUE(report_id,action),
 CONSTRAINT fk_report_audit_report FOREIGN KEY(report_id) REFERENCES cl_report(id) ON DELETE RESTRICT,
 CONSTRAINT fk_report_audit_actor FOREIGN KEY(actor_user_id) REFERENCES cl_user(id) ON DELETE RESTRICT,
 CONSTRAINT ck_report_audit_reason CHECK(CHAR_LENGTH(TRIM(reason)) BETWEEN 1 AND 1000),
 CONSTRAINT ck_report_audit_digest CHECK(CHAR_LENGTH(request_digest)=64),
 CONSTRAINT ck_report_audit_transition CHECK(
   (action='SUBMIT' AND previous_status IS NULL AND new_status='SUBMITTED' AND previous_version IS NULL AND new_version=0 AND decision IS NULL)
   OR (action='ACCEPT' AND previous_status='SUBMITTED' AND new_status='IN_REVIEW' AND previous_version>=0 AND new_version=previous_version+1 AND decision IS NULL)
   OR (action='DECIDE' AND previous_status='IN_REVIEW' AND new_status='RESOLVED' AND previous_version>=1 AND new_version=previous_version+1 AND decision IN ('UPHELD','DISMISSED'))
 )
);
CREATE INDEX idx_report_audit_timeline ON cl_report_audit(report_id,created_at,id);
