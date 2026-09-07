ALTER TABLE ai_provider_usage
    ADD COLUMN thoughts_token_count bigint
        CHECK (thoughts_token_count IS NULL OR thoughts_token_count >= 0),
    ADD COLUMN finish_reason varchar(64);

ALTER TABLE generation_model_benchmarks
    ADD COLUMN thoughts_token_count bigint
        CHECK (thoughts_token_count IS NULL OR thoughts_token_count >= 0),
    ADD COLUMN finish_reason varchar(64);
