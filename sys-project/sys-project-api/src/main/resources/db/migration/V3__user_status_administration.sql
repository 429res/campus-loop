ALTER TABLE cl_user ADD COLUMN version INT NOT NULL DEFAULT 0;
CREATE INDEX idx_user_role_id_status ON cl_user(role,id,status);

CREATE TABLE cl_user_status_audit (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 target_user_id BIGINT NOT NULL,
 operator_user_id BIGINT NOT NULL,
 previous_status VARCHAR(16) NOT NULL,
 new_status VARCHAR(16) NOT NULL,
 reason VARCHAR(500) NOT NULL,
 previous_version INT NOT NULL,
 new_version INT NOT NULL,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 CONSTRAINT fk_user_status_audit_target FOREIGN KEY(target_user_id) REFERENCES cl_user(id),
 CONSTRAINT fk_user_status_audit_operator FOREIGN KEY(operator_user_id) REFERENCES cl_user(id),
 CONSTRAINT ck_user_status_audit_previous CHECK(previous_status IN ('ACTIVE','DISABLED')),
 CONSTRAINT ck_user_status_audit_new CHECK(new_status IN ('ACTIVE','DISABLED'))
);
CREATE INDEX idx_user_status_audit_target ON cl_user_status_audit(target_user_id,created_at);
