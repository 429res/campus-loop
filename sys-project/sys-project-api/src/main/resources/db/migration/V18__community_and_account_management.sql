CREATE TABLE cl_account_audit (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 target_user_id BIGINT NOT NULL,actor_user_id BIGINT NOT NULL,
 action VARCHAR(32) NOT NULL,reason VARCHAR(500) NOT NULL,created_at TIMESTAMP NOT NULL,
 FOREIGN KEY(target_user_id) REFERENCES cl_user(id) ON DELETE RESTRICT,
 FOREIGN KEY(actor_user_id) REFERENCES cl_user(id) ON DELETE RESTRICT
);
CREATE INDEX idx_account_audit_target ON cl_account_audit(target_user_id,created_at,id);
CREATE TABLE cl_community_post (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, author_id BIGINT NOT NULL,
 body VARCHAR(2000) NOT NULL, item_id BIGINT NULL, image_url VARCHAR(255) NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'PUBLISHED',version INT NOT NULL DEFAULT 0,
 request_key VARCHAR(64) NOT NULL,created_at TIMESTAMP NOT NULL,
 FOREIGN KEY(author_id) REFERENCES cl_user(id) ON DELETE RESTRICT,
 FOREIGN KEY(item_id) REFERENCES cl_item(id) ON DELETE RESTRICT,
 UNIQUE(author_id,request_key),
 CHECK(status IN ('PUBLISHED','HIDDEN','DELETED'))
);
CREATE INDEX idx_community_timeline ON cl_community_post(status,created_at,id);
CREATE TABLE cl_community_reply (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,post_id BIGINT NOT NULL,author_id BIGINT NOT NULL,
 body VARCHAR(1000) NOT NULL,status VARCHAR(16) NOT NULL DEFAULT 'PUBLISHED',
 request_key VARCHAR(64) NOT NULL,created_at TIMESTAMP NOT NULL,
 FOREIGN KEY(post_id) REFERENCES cl_community_post(id) ON DELETE RESTRICT,
 FOREIGN KEY(author_id) REFERENCES cl_user(id) ON DELETE RESTRICT,
 UNIQUE(author_id,request_key),CHECK(status IN ('PUBLISHED','DELETED'))
);
CREATE INDEX idx_community_replies ON cl_community_reply(post_id,status,id);
CREATE TABLE cl_community_like (
 post_id BIGINT NOT NULL,user_id BIGINT NOT NULL,PRIMARY KEY(post_id,user_id),
 FOREIGN KEY(post_id) REFERENCES cl_community_post(id) ON DELETE RESTRICT,
 FOREIGN KEY(user_id) REFERENCES cl_user(id) ON DELETE RESTRICT
);
CREATE TABLE cl_community_report (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,post_id BIGINT NOT NULL,reporter_id BIGINT NOT NULL,
 reason VARCHAR(500) NOT NULL,status VARCHAR(16) NOT NULL DEFAULT 'OPEN',created_at TIMESTAMP NOT NULL,
 FOREIGN KEY(post_id) REFERENCES cl_community_post(id) ON DELETE RESTRICT,
 FOREIGN KEY(reporter_id) REFERENCES cl_user(id) ON DELETE RESTRICT,
 UNIQUE(post_id,reporter_id),CHECK(status IN ('OPEN','RESOLVED'))
);
CREATE TABLE cl_community_audit (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,post_id BIGINT NOT NULL,actor_id BIGINT NOT NULL,
 action VARCHAR(32) NOT NULL,reason VARCHAR(500) NOT NULL,created_at TIMESTAMP NOT NULL,
 FOREIGN KEY(post_id) REFERENCES cl_community_post(id) ON DELETE RESTRICT,
 FOREIGN KEY(actor_id) REFERENCES cl_user(id) ON DELETE RESTRICT
);
