-- Existing timestamps remain untouched; no delivery claims or dispute verdicts are invented.
ALTER TABLE cl_exchange_participant ADD COLUMN handed_off_note VARCHAR(1000) NULL;
ALTER TABLE cl_exchange_participant ADD COLUMN received_note VARCHAR(1000) NULL;
ALTER TABLE cl_exchange ADD COLUMN disputed_by BIGINT NULL;
ALTER TABLE cl_exchange ADD COLUMN dispute_reason VARCHAR(1000) NULL;
ALTER TABLE cl_exchange ADD COLUMN disputed_at TIMESTAMP NULL;
ALTER TABLE cl_exchange ADD CONSTRAINT fk_exchange_disputer FOREIGN KEY(disputed_by) REFERENCES cl_user(id) ON DELETE RESTRICT;
ALTER TABLE cl_exchange_event DROP CONSTRAINT ck_exchange_event_type;
ALTER TABLE cl_exchange_event ADD CONSTRAINT ck_exchange_event_type CHECK(event_type IN ('CONFIRMED','CANCELLED','EXPIRED','HANDED_OFF','RECEIVED','DISPUTED'));
