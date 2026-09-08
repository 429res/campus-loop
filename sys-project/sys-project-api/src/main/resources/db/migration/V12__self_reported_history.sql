-- Preserve existing B-04 events and public uploads; no new claims or credibility upgrades.
ALTER TABLE cl_item_history MODIFY COLUMN occurred_at TIMESTAMP NULL;
ALTER TABLE cl_item_history ADD COLUMN corrects_event_id BIGINT NULL;
ALTER TABLE cl_item_history ADD COLUMN ownership_started_at TIMESTAMP NULL;
ALTER TABLE cl_item_history ADD COLUMN ownership_ended_at TIMESTAMP NULL;
ALTER TABLE cl_item_history ADD CONSTRAINT uq_history_author_source UNIQUE(id,item_id,source_user_id,evidence_level);
ALTER TABLE cl_item_history ADD CONSTRAINT fk_history_correction FOREIGN KEY(corrects_event_id,item_id,source_user_id,evidence_level) REFERENCES cl_item_history(id,item_id,source_user_id,evidence_level) ON DELETE RESTRICT;
ALTER TABLE cl_item_history ADD CONSTRAINT ck_history_self_correction CHECK(corrects_event_id IS NULL OR evidence_level='SELF_REPORTED');
ALTER TABLE cl_item_history ADD CONSTRAINT uq_history_correction UNIQUE(corrects_event_id);
CREATE INDEX idx_history_item_recorded ON cl_item_history(item_id,recorded_at,id);
ALTER TABLE cl_upload ADD COLUMN visibility VARCHAR(24) NOT NULL DEFAULT 'PUBLIC';
ALTER TABLE cl_upload ADD CONSTRAINT ck_upload_visibility CHECK(visibility IN ('PUBLIC','PRIVATE_EVIDENCE'));
CREATE TABLE cl_history_evidence (
 history_id BIGINT NOT NULL,
 upload_id VARCHAR(36) NOT NULL,
 PRIMARY KEY(history_id,upload_id),
 CONSTRAINT fk_evidence_history FOREIGN KEY(history_id) REFERENCES cl_item_history(id) ON DELETE RESTRICT,
 CONSTRAINT fk_evidence_upload FOREIGN KEY(upload_id) REFERENCES cl_upload(id) ON DELETE RESTRICT
);
CREATE INDEX idx_history_evidence_upload ON cl_history_evidence(upload_id);
