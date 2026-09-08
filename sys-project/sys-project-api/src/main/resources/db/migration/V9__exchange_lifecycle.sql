-- Preserve old exchange rows: no invented cancellation actor, reason, time or historical events.
ALTER TABLE cl_exchange ADD COLUMN cancelled_by BIGINT NULL;
ALTER TABLE cl_exchange ADD COLUMN cancellation_reason VARCHAR(1000) NULL;
ALTER TABLE cl_exchange ADD COLUMN cancelled_at TIMESTAMP NULL;
ALTER TABLE cl_exchange ADD CONSTRAINT fk_exchange_canceller FOREIGN KEY(cancelled_by) REFERENCES cl_user(id) ON DELETE RESTRICT;
CREATE TABLE cl_exchange_event (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 exchange_id BIGINT NOT NULL,
 actor_id BIGINT NULL,
 event_type VARCHAR(24) NOT NULL,
 previous_status VARCHAR(24) NOT NULL,
 new_status VARCHAR(24) NOT NULL,
 previous_version INT NOT NULL,
 new_version INT NOT NULL,
 reason VARCHAR(1000) NULL,
 occurred_at TIMESTAMP NOT NULL,
 CONSTRAINT fk_exchange_event_exchange FOREIGN KEY(exchange_id) REFERENCES cl_exchange(id) ON DELETE RESTRICT,
 CONSTRAINT fk_exchange_event_actor FOREIGN KEY(actor_id) REFERENCES cl_user(id) ON DELETE RESTRICT,
 CONSTRAINT uq_exchange_event_version UNIQUE(exchange_id,new_version),
 CONSTRAINT ck_exchange_event_type CHECK(event_type IN ('CONFIRMED','CANCELLED','EXPIRED')),
 CONSTRAINT ck_exchange_event_version CHECK(previous_version >= 0 AND new_version > previous_version)
);
