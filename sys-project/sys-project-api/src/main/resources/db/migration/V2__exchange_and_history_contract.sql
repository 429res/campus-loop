-- These tables establish the next iteration's persistence contract. No exchange writes are implemented yet.
CREATE TABLE cl_exchange (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 initiator_id BIGINT NOT NULL,
 status VARCHAR(24) NOT NULL,
 version INT NOT NULL DEFAULT 0,
 idempotency_key VARCHAR(64) NOT NULL,
 expires_at TIMESTAMP NOT NULL,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 CONSTRAINT fk_exchange_initiator FOREIGN KEY(initiator_id) REFERENCES cl_user(id),
 CONSTRAINT uq_exchange_request UNIQUE(initiator_id,idempotency_key),
 CONSTRAINT ck_exchange_status CHECK(status IN ('AWAITING_CONFIRMATION','READY','COMPLETED','CANCELLED','EXPIRED','DISPUTED'))
);
CREATE TABLE cl_exchange_participant (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 exchange_id BIGINT NOT NULL,
 user_id BIGINT NOT NULL,
 offered_item_id BIGINT NOT NULL,
 recipient_user_id BIGINT NOT NULL,
 confirmed_at TIMESTAMP NULL,
 handed_off_at TIMESTAMP NULL,
 received_at TIMESTAMP NULL,
 CONSTRAINT fk_participant_exchange FOREIGN KEY(exchange_id) REFERENCES cl_exchange(id),
 CONSTRAINT fk_participant_user FOREIGN KEY(user_id) REFERENCES cl_user(id),
 CONSTRAINT fk_participant_item FOREIGN KEY(offered_item_id) REFERENCES cl_item(id),
 CONSTRAINT fk_participant_recipient FOREIGN KEY(recipient_user_id) REFERENCES cl_user(id),
 CONSTRAINT uq_exchange_user UNIQUE(exchange_id,user_id),
 CONSTRAINT uq_exchange_item UNIQUE(exchange_id,offered_item_id)
);
-- One primary key per item makes competing active reservations mutually exclusive.
CREATE TABLE cl_item_hold (
 item_id BIGINT PRIMARY KEY,
 exchange_id BIGINT NOT NULL,
 expires_at TIMESTAMP NOT NULL,
 CONSTRAINT fk_hold_item FOREIGN KEY(item_id) REFERENCES cl_item(id),
 CONSTRAINT fk_hold_exchange FOREIGN KEY(exchange_id) REFERENCES cl_exchange(id)
);
CREATE INDEX idx_hold_expiry ON cl_item_hold(expires_at);
CREATE TABLE cl_item_history (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 item_id BIGINT NOT NULL,
 exchange_id BIGINT NULL,
 event_type VARCHAR(24) NOT NULL,
 description VARCHAR(2000) NOT NULL,
 evidence_level VARCHAR(24) NOT NULL DEFAULT 'SELF_REPORTED',
 source_user_id BIGINT NOT NULL,
 counterparty_user_id BIGINT NULL,
 verified_by_user_id BIGINT NULL,
 occurred_at TIMESTAMP NOT NULL,
 recorded_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 confirmed_at TIMESTAMP NULL,
 verified_at TIMESTAMP NULL,
 CONSTRAINT fk_history_item FOREIGN KEY(item_id) REFERENCES cl_item(id),
 CONSTRAINT fk_history_exchange FOREIGN KEY(exchange_id) REFERENCES cl_exchange(id),
 CONSTRAINT fk_history_source FOREIGN KEY(source_user_id) REFERENCES cl_user(id),
 CONSTRAINT fk_history_counterparty FOREIGN KEY(counterparty_user_id) REFERENCES cl_user(id),
 CONSTRAINT fk_history_verifier FOREIGN KEY(verified_by_user_id) REFERENCES cl_user(id),
 CONSTRAINT ck_history_level CHECK(evidence_level IN ('SELF_REPORTED','BOTH_CONFIRMED','ADMIN_VERIFIED'))
);
