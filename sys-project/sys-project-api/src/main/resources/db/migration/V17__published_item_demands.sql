ALTER TABLE cl_demand ADD COLUMN source_item_id BIGINT;
ALTER TABLE cl_demand ADD CONSTRAINT uk_demand_source_item UNIQUE(source_item_id);
ALTER TABLE cl_demand ADD CONSTRAINT fk_demand_source_item FOREIGN KEY(source_item_id) REFERENCES cl_item(id) ON DELETE RESTRICT;

INSERT INTO cl_demand(owner_id,category_id,description,preferred_tags_json,status,version,created_at,updated_at,source_item_id)
SELECT owner_id,wanted_category_id,CONCAT('用「',title,'」交换'),wanted_tags_json,'ACTIVE',0,created_at,CURRENT_TIMESTAMP,id
FROM cl_item WHERE wanted_category_id IS NOT NULL AND status <> 'EXCHANGED';
INSERT INTO cl_demand_item(demand_id,item_id)
SELECT id,source_item_id FROM cl_demand WHERE source_item_id IS NOT NULL;
