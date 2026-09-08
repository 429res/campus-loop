-- Preserve V2 historical rows: missing creation metadata must never be guessed on replay.
ALTER TABLE cl_exchange ADD COLUMN request_digest VARCHAR(64) NULL;
ALTER TABLE cl_exchange ADD COLUMN rule_version VARCHAR(32) NULL;
ALTER TABLE cl_exchange ADD COLUMN creation_snapshot LONGTEXT NULL;
CREATE TABLE cl_exchange_demand (
 exchange_id BIGINT NOT NULL,
 demand_id BIGINT NOT NULL,
 offered_item_id BIGINT NOT NULL,
 demand_version INT NOT NULL,
 demand_snapshot LONGTEXT NOT NULL,
 PRIMARY KEY(exchange_id,demand_id),
 CONSTRAINT uq_exchange_demand_offer UNIQUE(exchange_id,offered_item_id),
 CONSTRAINT fk_exchange_demand_exchange FOREIGN KEY(exchange_id) REFERENCES cl_exchange(id) ON DELETE RESTRICT,
 CONSTRAINT fk_exchange_demand_demand FOREIGN KEY(demand_id) REFERENCES cl_demand(id) ON DELETE RESTRICT,
 CONSTRAINT fk_exchange_demand_item FOREIGN KEY(offered_item_id) REFERENCES cl_item(id) ON DELETE RESTRICT,
 CONSTRAINT ck_exchange_demand_version CHECK(demand_version >= 0)
);
CREATE INDEX idx_exchange_demand_reference ON cl_exchange_demand(demand_id,exchange_id);
