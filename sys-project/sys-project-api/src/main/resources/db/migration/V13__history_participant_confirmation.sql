-- Append-only source chain; no backfill of claims, confirmations or evidence consent.
ALTER TABLE cl_item_history ADD CONSTRAINT uq_history_exchange UNIQUE(id,exchange_id);
CREATE TABLE cl_history_confirmation_request (
 history_id BIGINT PRIMARY KEY,
 exchange_id BIGINT NOT NULL,
 snapshot_hash CHAR(64) NOT NULL,
 snapshot_json TEXT NOT NULL,
 requested_at TIMESTAMP NOT NULL,
 CONSTRAINT uq_confirmation_exchange UNIQUE(history_id,exchange_id),
 CONSTRAINT uq_confirmation_snapshot UNIQUE(history_id,snapshot_hash),
 CONSTRAINT fk_confirmation_history FOREIGN KEY(history_id,exchange_id) REFERENCES cl_item_history(id,exchange_id) ON DELETE RESTRICT
);
CREATE TABLE cl_history_confirmation_member (
 history_id BIGINT NOT NULL,
 exchange_id BIGINT NOT NULL,
 user_id BIGINT NOT NULL,
 PRIMARY KEY(history_id,user_id),
 CONSTRAINT fk_confirmation_roster FOREIGN KEY(history_id,exchange_id) REFERENCES cl_history_confirmation_request(history_id,exchange_id) ON DELETE RESTRICT,
 CONSTRAINT fk_confirmation_participant FOREIGN KEY(exchange_id,user_id) REFERENCES cl_exchange_participant(exchange_id,user_id) ON DELETE RESTRICT
);
CREATE TABLE cl_history_confirmation (
 history_id BIGINT NOT NULL,
 user_id BIGINT NOT NULL,
 snapshot_hash CHAR(64) NOT NULL,
 confirmed_at TIMESTAMP NOT NULL,
 PRIMARY KEY(history_id,user_id),
 CONSTRAINT fk_confirmation_member FOREIGN KEY(history_id,user_id) REFERENCES cl_history_confirmation_member(history_id,user_id) ON DELETE RESTRICT,
 CONSTRAINT fk_confirmation_version FOREIGN KEY(history_id,snapshot_hash) REFERENCES cl_history_confirmation_request(history_id,snapshot_hash) ON DELETE RESTRICT
);
CREATE TABLE cl_history_confirmation_withdrawal (
 history_id BIGINT PRIMARY KEY,
 snapshot_hash CHAR(64) NOT NULL,
 actor_id BIGINT NOT NULL,
 reason VARCHAR(500) NOT NULL,
 withdrawn_at TIMESTAMP NOT NULL,
 CONSTRAINT fk_withdrawal_snapshot FOREIGN KEY(history_id,snapshot_hash) REFERENCES cl_history_confirmation_request(history_id,snapshot_hash) ON DELETE RESTRICT,
 CONSTRAINT fk_withdrawal_actor FOREIGN KEY(actor_id) REFERENCES cl_user(id) ON DELETE RESTRICT
);
