-- Existing S3 notifications identify the initial attempt by upload_id.
ALTER TABLE videos ADD COLUMN active_attempt_id UUID;
UPDATE videos SET active_attempt_id = upload_id;
ALTER TABLE videos ALTER COLUMN active_attempt_id SET NOT NULL;
