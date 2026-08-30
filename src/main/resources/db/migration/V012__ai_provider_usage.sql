ALTER TABLE ai_jobs ADD COLUMN cache_fingerprint char(64);
UPDATE ai_jobs SET cache_fingerprint = CASE
    WHEN char_length(idempotency_key) = 64 AND idempotency_key ~ '^[0-9a-f]{64}$' THEN idempotency_key
    ELSE md5(idempotency_key) || md5(idempotency_key || ':cache')
END;
ALTER TABLE ai_jobs ALTER COLUMN cache_fingerprint SET NOT NULL;

CREATE FUNCTION default_ai_job_cache_fingerprint()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.cache_fingerprint IS NULL THEN
        NEW.cache_fingerprint := CASE
            WHEN char_length(NEW.idempotency_key) = 64 AND NEW.idempotency_key ~ '^[0-9a-f]{64}$'
                THEN NEW.idempotency_key
            ELSE md5(NEW.idempotency_key) || md5(NEW.idempotency_key || ':cache')
        END;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER ai_jobs_cache_fingerprint_default
BEFORE INSERT ON ai_jobs
FOR EACH ROW EXECUTE FUNCTION default_ai_job_cache_fingerprint();

CREATE UNIQUE INDEX ai_jobs_active_cache_fingerprint_uidx
    ON ai_jobs(owner_id, cache_fingerprint) WHERE status IN ('queued', 'running');

CREATE TABLE ai_provider_usage (
    id uuid PRIMARY KEY,
    job_id uuid REFERENCES ai_jobs(id) ON DELETE SET NULL,
    owner_id uuid NOT NULL,
    operation varchar(64) NOT NULL CHECK (char_length(btrim(operation)) > 0),
    provider varchar(64) NOT NULL CHECK (char_length(btrim(provider)) > 0),
    model_id varchar(255) NOT NULL CHECK (char_length(btrim(model_id)) > 0),
    status varchar(16) NOT NULL CHECK (status IN ('started', 'succeeded', 'failed')),
    unit_type varchar(32) NOT NULL CHECK (char_length(btrim(unit_type)) > 0),
    unit_count bigint CHECK (unit_count IS NULL OR unit_count >= 0),
    estimated_cost_microusd bigint CHECK (estimated_cost_microusd >= 0),
    error_code varchar(100),
    latency_ms bigint CHECK (latency_ms IS NULL OR latency_ms >= 0),
    started_at timestamptz NOT NULL,
    finished_at timestamptz,
    CHECK ((status = 'started') = (finished_at IS NULL)),
    CHECK (started_at <= COALESCE(finished_at, started_at))
);

CREATE INDEX ai_provider_usage_job_idx ON ai_provider_usage(job_id, started_at, id);
CREATE INDEX ai_provider_usage_owner_started_idx ON ai_provider_usage(owner_id, started_at, id);
