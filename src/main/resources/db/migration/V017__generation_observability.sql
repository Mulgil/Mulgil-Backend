ALTER TABLE ai_provider_usage
    ADD COLUMN prompt_token_count bigint CHECK (prompt_token_count IS NULL OR prompt_token_count >= 0),
    ADD COLUMN candidate_token_count bigint CHECK (candidate_token_count IS NULL OR candidate_token_count >= 0),
    ADD COLUMN total_token_count bigint CHECK (total_token_count IS NULL OR total_token_count >= 0),
    ADD COLUMN cached_content_token_count bigint CHECK (cached_content_token_count IS NULL OR cached_content_token_count >= 0),
    ADD COLUMN first_response_latency_ms bigint CHECK (first_response_latency_ms IS NULL OR first_response_latency_ms >= 0);

ALTER TABLE ai_jobs
    ADD COLUMN progress_stage varchar(16),
    ADD COLUMN progress_updated_at timestamptz,
    ADD CONSTRAINT ai_jobs_progress_stage_check CHECK (
        (progress_stage IS NULL AND progress_updated_at IS NULL)
        OR (
            job_type IN ('preview_generate', 'review_generate', 'exam_summary_generate', 'exam_quiz_generate')
            AND progress_stage IN ('preparing', 'generating', 'validating', 'publishing')
            AND progress_updated_at IS NOT NULL
        )
    );
