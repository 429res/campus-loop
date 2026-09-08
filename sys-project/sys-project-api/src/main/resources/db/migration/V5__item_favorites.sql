-- Private per-user bookmarks; visibility is read from the current item, never copied here.
CREATE TABLE cl_favorite (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 user_id BIGINT NOT NULL,
 item_id BIGINT NOT NULL,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 CONSTRAINT uq_favorite_user_item UNIQUE(user_id,item_id),
 CONSTRAINT fk_favorite_user FOREIGN KEY(user_id) REFERENCES cl_user(id) ON DELETE RESTRICT,
 CONSTRAINT fk_favorite_item FOREIGN KEY(item_id) REFERENCES cl_item(id) ON DELETE RESTRICT
);
CREATE INDEX idx_favorite_user_created ON cl_favorite(user_id,created_at,id);
CREATE INDEX idx_favorite_item ON cl_favorite(item_id);
