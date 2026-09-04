ALTER TABLE materials
    ADD COLUMN upload_expires_at timestamptz;

-- Existing pending rows have no trustworthy signed-URL expiry and must not keep
-- consuming the per-session quota after this migration.
UPDATE materials
SET status = 'cancelled', updated_at = CURRENT_TIMESTAMP
WHERE status = 'created';

ALTER TABLE materials
    ADD CONSTRAINT materials_created_upload_expiry_check
        CHECK (status <> 'created' OR upload_expires_at IS NOT NULL);

CREATE INDEX materials_pending_upload_expiry_idx
    ON materials(upload_expires_at, id)
    WHERE status = 'created';
