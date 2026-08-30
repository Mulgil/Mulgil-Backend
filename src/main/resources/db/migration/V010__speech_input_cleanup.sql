CREATE TABLE speech_input_cleanups (
    job_id uuid PRIMARY KEY,
    owner_id uuid NOT NULL,
    object_uris text[] NOT NULL CHECK (cardinality(object_uris) > 0),
    not_before timestamptz NOT NULL,
    created_at timestamptz NOT NULL,
    CHECK (not_before >= created_at)
);

CREATE INDEX speech_input_cleanups_due_idx
    ON speech_input_cleanups(not_before, job_id);
CREATE INDEX speech_input_cleanups_owner_idx
    ON speech_input_cleanups(owner_id, created_at, job_id);
