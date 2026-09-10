ALTER TABLE cl_user ADD COLUMN last_login_address VARCHAR(64) NULL;
-- Daily business notifications remain in-app; remove only their pending mail deliveries.
DELETE FROM cl_mail_outbox WHERE notification_id IN (SELECT id FROM cl_notification WHERE kind <> 'ACCOUNT_SECURITY');
