CREATE TABLE resource_object_deletions (
    object_key text PRIMARY KEY CHECK (char_length(btrim(object_key)) > 0),
    not_before timestamptz NOT NULL,
    attempt_count integer NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    status varchar(16) NOT NULL CHECK (status IN ('pending', 'failed')),
    last_error varchar(500),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CHECK (not_before >= created_at),
    CHECK ((status = 'pending' AND attempt_count < 3) OR (status = 'failed' AND attempt_count >= 3))
);

CREATE INDEX resource_object_deletions_due_idx
    ON resource_object_deletions(not_before, object_key) WHERE status = 'pending';
