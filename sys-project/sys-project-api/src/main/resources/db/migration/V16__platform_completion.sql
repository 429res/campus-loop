ALTER TABLE cl_user ADD COLUMN avatar_url VARCHAR(255) NULL;
ALTER TABLE cl_user ADD COLUMN bio VARCHAR(300) NULL;
ALTER TABLE cl_user ADD COLUMN campus VARCHAR(100) NULL;
ALTER TABLE cl_user ADD COLUMN contact VARCHAR(160) NULL;
ALTER TABLE cl_user ADD COLUMN admin_permissions VARCHAR(255) NOT NULL DEFAULT 'ALL';
UPDATE cl_user SET admin_permissions='' WHERE role='USER';
CREATE TABLE cl_user_access_audit (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 target_user_id BIGINT NOT NULL, actor_user_id BIGINT NOT NULL,
 previous_role VARCHAR(16) NOT NULL, new_role VARCHAR(16) NOT NULL,
 previous_permissions VARCHAR(255) NOT NULL, new_permissions VARCHAR(255) NOT NULL,
 previous_version INT NOT NULL, new_version INT NOT NULL,
 reason VARCHAR(500) NOT NULL, created_at TIMESTAMP NOT NULL,
 CONSTRAINT fk_access_target FOREIGN KEY(target_user_id) REFERENCES cl_user(id) ON DELETE RESTRICT,
 CONSTRAINT fk_access_actor FOREIGN KEY(actor_user_id) REFERENCES cl_user(id) ON DELETE RESTRICT,
 CONSTRAINT uq_access_version UNIQUE(target_user_id,new_version)
);
CREATE TABLE cl_notification (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT NOT NULL,
 kind VARCHAR(32) NOT NULL, title VARCHAR(100) NOT NULL, body VARCHAR(1200) NOT NULL,
 link VARCHAR(255) NOT NULL, source_key VARCHAR(100) NOT NULL,
 created_at TIMESTAMP NOT NULL, read_at TIMESTAMP NULL,
 CONSTRAINT fk_notification_user FOREIGN KEY(user_id) REFERENCES cl_user(id) ON DELETE RESTRICT,
 CONSTRAINT uq_notification_source UNIQUE(user_id,source_key)
);
CREATE INDEX idx_notification_inbox ON cl_notification(user_id,created_at,id);
CREATE TABLE cl_exchange_resolution (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, exchange_id BIGINT NOT NULL, actor_user_id BIGINT NOT NULL,
 decision VARCHAR(32) NOT NULL, reason VARCHAR(1000) NOT NULL, return_confirmed BOOLEAN NOT NULL,
 previous_version INT NOT NULL, new_version INT NOT NULL, created_at TIMESTAMP NOT NULL,
 CONSTRAINT fk_resolution_exchange FOREIGN KEY(exchange_id) REFERENCES cl_exchange(id) ON DELETE RESTRICT,
 CONSTRAINT fk_resolution_actor FOREIGN KEY(actor_user_id) REFERENCES cl_user(id) ON DELETE RESTRICT,
 CONSTRAINT uq_resolution_version UNIQUE(exchange_id,previous_version),
 CONSTRAINT ck_resolution_decision CHECK(decision IN ('RESUME','CANCEL'))
);
ALTER TABLE cl_exchange_event DROP CONSTRAINT ck_exchange_event_type;
ALTER TABLE cl_exchange_event ADD CONSTRAINT ck_exchange_event_type CHECK(event_type IN ('CONFIRMED','CANCELLED','EXPIRED','HANDED_OFF','RECEIVED','DISPUTED','DISPUTE_RESUMED','ADMIN_CANCELLED'));
