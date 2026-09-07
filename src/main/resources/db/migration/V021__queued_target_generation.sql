ALTER TABLE ai_jobs DROP CONSTRAINT ai_jobs_job_type_check;
ALTER TABLE ai_jobs ADD CONSTRAINT ai_jobs_job_type_check CHECK (job_type IN
    ('pdf_extract', 'pdf_ocr', 'handwriting_ocr', 'stt', 'chunk_embed', 'preview_generate',
     'review_generate', 'preview_mindmap_generate', 'review_mindmap_generate',
     'preview_quiz_generate', 'review_quiz_generate', 'exam_summary_generate',
     'exam_quiz_generate', 'target_generate', 'notification_send'));

ALTER TABLE ai_jobs DROP CONSTRAINT ai_jobs_progress_stage_check;
ALTER TABLE ai_jobs ADD CONSTRAINT ai_jobs_progress_stage_check CHECK (
    (progress_stage IS NULL AND progress_updated_at IS NULL)
    OR (
        job_type IN ('preview_generate', 'review_generate',
                     'preview_mindmap_generate', 'review_mindmap_generate',
                     'preview_quiz_generate', 'review_quiz_generate',
                     'exam_summary_generate', 'exam_quiz_generate', 'target_generate')
        AND progress_stage IN ('preparing', 'generating', 'validating', 'publishing')
        AND progress_updated_at IS NOT NULL
    )
);

ALTER TABLE ai_jobs ADD CONSTRAINT ai_jobs_target_scope_key
    UNIQUE (id, owner_id, course_id, session_id);

CREATE TABLE selected_topic_generations (
    job_id uuid PRIMARY KEY,
    owner_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    course_id uuid NOT NULL,
    session_id uuid NOT NULL,
    payload_object_key text NOT NULL UNIQUE
        CHECK (payload_object_key LIKE
            'temporary/target-generations/' || owner_id::text || '/%'),
    payload_hash char(64) NOT NULL CHECK (payload_hash ~ '^[0-9a-f]{64}$'),
    payload_expires_at timestamptz NOT NULL,
    selected_chunk_ids uuid[],
    result_json jsonb CHECK (result_json IS NULL OR jsonb_typeof(result_json) = 'object'),
    selected_count integer CHECK (selected_count IS NULL OR selected_count > 0),
    created_at timestamptz NOT NULL,
    completed_at timestamptz,
    FOREIGN KEY (owner_id, course_id, session_id)
        REFERENCES class_sessions(owner_id, course_id, id) ON DELETE CASCADE,
    FOREIGN KEY (job_id, owner_id, course_id, session_id)
        REFERENCES ai_jobs(id, owner_id, course_id, session_id) ON DELETE CASCADE,
    CHECK (payload_expires_at > created_at),
    CHECK ((result_json IS NULL) = (completed_at IS NULL)),
    CHECK ((result_json IS NULL) = (selected_count IS NULL)),
    CHECK (result_json IS NULL OR
        (selected_chunk_ids IS NOT NULL AND cardinality(selected_chunk_ids) = selected_count))
);

CREATE INDEX selected_topic_generations_owner_job_idx
    ON selected_topic_generations(owner_id, job_id);
CREATE INDEX selected_topic_generations_payload_expiry_idx
    ON selected_topic_generations(payload_expires_at, job_id);
