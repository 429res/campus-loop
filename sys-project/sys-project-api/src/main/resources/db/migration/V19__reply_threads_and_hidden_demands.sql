ALTER TABLE cl_community_reply ADD COLUMN parent_reply_id BIGINT NULL;
ALTER TABLE cl_community_reply ADD CONSTRAINT fk_reply_parent FOREIGN KEY(parent_reply_id) REFERENCES cl_community_reply(id) ON DELETE SET NULL;
CREATE INDEX idx_reply_parent ON cl_community_reply(parent_reply_id);
UPDATE cl_demand SET status='INACTIVE',version=version+1,updated_at=CURRENT_TIMESTAMP
WHERE source_item_id IN (SELECT id FROM cl_item WHERE status='HIDDEN') AND status='ACTIVE';
