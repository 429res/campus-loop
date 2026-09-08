-- Independent demands do not migrate or update the legacy wanted fields on cl_item.
CREATE TABLE cl_demand (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 owner_id BIGINT NOT NULL,
 category_id BIGINT NOT NULL,
 description VARCHAR(2000) NOT NULL,
 preferred_tags_json VARCHAR(2000) NOT NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
 version INT NOT NULL DEFAULT 0,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 CONSTRAINT fk_demand_owner FOREIGN KEY(owner_id) REFERENCES cl_user(id) ON DELETE RESTRICT,
 CONSTRAINT fk_demand_category FOREIGN KEY(category_id) REFERENCES cl_category(id) ON DELETE RESTRICT,
 CONSTRAINT ck_demand_status CHECK(status IN ('ACTIVE','INACTIVE','DELETED')),
 CONSTRAINT ck_demand_version CHECK(version >= 0)
);
CREATE INDEX idx_demand_owner_created ON cl_demand(owner_id,created_at,id);
CREATE INDEX idx_demand_owner_status ON cl_demand(owner_id,status,created_at,id);

-- Many-to-many candidate choices only; no duplicate item entity, ownership, or reservation.
-- API deletion leaves the demand and its current associations intact as a tombstone.
CREATE TABLE cl_demand_item (
 demand_id BIGINT NOT NULL,
 item_id BIGINT NOT NULL,
 PRIMARY KEY(demand_id,item_id),
 CONSTRAINT fk_demand_item_demand FOREIGN KEY(demand_id) REFERENCES cl_demand(id) ON DELETE RESTRICT,
 CONSTRAINT fk_demand_item_item FOREIGN KEY(item_id) REFERENCES cl_item(id) ON DELETE RESTRICT
);
CREATE INDEX idx_demand_item_item ON cl_demand_item(item_id);
