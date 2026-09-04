ALTER TABLE exam_resources
    ADD COLUMN upload_expires_at timestamptz;

-- Existing pending rows have no trustworthy signed-URL expiry and must not
-- remain finishable after this migration.
UPDATE exam_resources
SET status = 'cancelled', updated_at = CURRENT_TIMESTAMP
WHERE status = 'created';

ALTER TABLE exam_resources
    ADD CONSTRAINT exam_resources_created_upload_expiry_check
        CHECK (status <> 'created' OR upload_expires_at IS NOT NULL);

CREATE INDEX exam_resources_pending_upload_expiry_idx
    ON exam_resources(upload_expires_at, id)
    WHERE status = 'created';
