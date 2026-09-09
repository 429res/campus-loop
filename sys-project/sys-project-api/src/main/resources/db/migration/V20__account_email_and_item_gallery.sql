ALTER TABLE cl_user ADD COLUMN email VARCHAR(254) NULL;
ALTER TABLE cl_user ADD COLUMN email_verified BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE cl_user ADD COLUMN legacy_exchange_access BOOLEAN NOT NULL DEFAULT FALSE;
UPDATE cl_user SET legacy_exchange_access=TRUE;
ALTER TABLE cl_user ADD COLUMN cover_url VARCHAR(255) NULL;
ALTER TABLE cl_user ADD COLUMN mail_notifications BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE cl_user ADD COLUMN deleted_at TIMESTAMP NULL;
ALTER TABLE cl_user ADD CONSTRAINT uq_user_email UNIQUE(email);
ALTER TABLE cl_item ADD COLUMN image_urls_json TEXT NULL;
CREATE TABLE cl_item_spotlight (
 item_id BIGINT PRIMARY KEY, sort_order INT NOT NULL DEFAULT 0,
 FOREIGN KEY(item_id) REFERENCES cl_item(id)
);
CREATE TABLE cl_email_code (
 email VARCHAR(254) NOT NULL, purpose VARCHAR(16) NOT NULL, user_id BIGINT NOT NULL DEFAULT 0,
 code_hash VARCHAR(64) NOT NULL, expires_at TIMESTAMP NOT NULL, sent_at TIMESTAMP NOT NULL,
 attempts INT NOT NULL DEFAULT 0, consumed BOOLEAN NOT NULL DEFAULT FALSE,
 PRIMARY KEY(email,purpose,user_id)
);
CREATE TABLE cl_mail_outbox (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, notification_id BIGINT NOT NULL UNIQUE,
 attempts INT NOT NULL DEFAULT 0, next_attempt_at TIMESTAMP NOT NULL, sent_at TIMESTAMP NULL,
 FOREIGN KEY(notification_id) REFERENCES cl_notification(id)
);
