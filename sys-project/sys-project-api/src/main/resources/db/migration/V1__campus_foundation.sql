CREATE TABLE cl_user (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 username VARCHAR(64) NOT NULL UNIQUE,
 password_hash VARCHAR(100) NULL,
 display_name VARCHAR(64) NOT NULL,
 role VARCHAR(16) NOT NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 CONSTRAINT ck_user_role CHECK (role IN ('ADMIN','USER')),
 CONSTRAINT ck_user_status CHECK (status IN ('ACTIVE','DISABLED'))
);
CREATE TABLE cl_category (id BIGINT AUTO_INCREMENT PRIMARY KEY, name VARCHAR(64) NOT NULL UNIQUE);
INSERT INTO cl_category(id,name) VALUES (1,'图书教材'),(2,'数码电子'),(3,'运动户外'),(4,'生活好物'),(5,'兴趣手作'),(6,'绿色植物');
CREATE TABLE cl_item (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 owner_id BIGINT NOT NULL,
 title VARCHAR(100) NOT NULL,
 description VARCHAR(2000) NOT NULL,
 category_id BIGINT NOT NULL,
 condition_level INT NOT NULL,
 tags_json VARCHAR(500) NOT NULL,
 wanted_category_id BIGINT NOT NULL,
 wanted_tags_json VARCHAR(500) NOT NULL,
 image_url VARCHAR(255) NULL,
 status VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE',
 version INT NOT NULL DEFAULT 0,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 CONSTRAINT fk_item_owner FOREIGN KEY(owner_id) REFERENCES cl_user(id),
 CONSTRAINT fk_item_category FOREIGN KEY(category_id) REFERENCES cl_category(id),
 CONSTRAINT fk_item_wanted FOREIGN KEY(wanted_category_id) REFERENCES cl_category(id),
 CONSTRAINT ck_item_condition CHECK(condition_level BETWEEN 1 AND 5),
 CONSTRAINT ck_item_status CHECK(status IN ('DRAFT','PENDING_REVIEW','AVAILABLE','RESERVED','EXCHANGED','HIDDEN'))
);
CREATE INDEX idx_item_browse ON cl_item(status,category_id,created_at);
CREATE INDEX idx_item_owner ON cl_item(owner_id);
CREATE TABLE cl_auth_session (
 id VARCHAR(36) PRIMARY KEY,
 user_id BIGINT NOT NULL,
 expires_at TIMESTAMP NOT NULL,
 CONSTRAINT fk_session_user FOREIGN KEY(user_id) REFERENCES cl_user(id)
);
CREATE INDEX idx_session_expiry ON cl_auth_session(expires_at);
CREATE TABLE cl_upload (
 id VARCHAR(36) PRIMARY KEY,
 owner_id BIGINT NOT NULL,
 url VARCHAR(255) NOT NULL UNIQUE,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 CONSTRAINT fk_upload_user FOREIGN KEY(owner_id) REFERENCES cl_user(id)
);
