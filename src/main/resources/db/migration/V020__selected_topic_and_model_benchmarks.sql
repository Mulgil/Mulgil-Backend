CREATE TABLE selected_topic_retrieval_metrics (
    id uuid PRIMARY KEY,
    owner_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    query_hash char(64) NOT NULL,
    intent varchar(16) NOT NULL CHECK (intent IN ('summary', 'mindmap', 'quiz')),
    requested_k integer NOT NULL CHECK (requested_k BETWEEN 1 AND 20),
    candidate_count integer NOT NULL CHECK (candidate_count >= 0),
    selected_count integer NOT NULL CHECK (selected_count BETWEEN 0 AND requested_k),
    latency_ms bigint NOT NULL CHECK (latency_ms >= 0),
    embedding_model varchar(255),
    source_type_counts jsonb NOT NULL CHECK (jsonb_typeof(source_type_counts) = 'object'),
    failure_code varchar(100),
    retention_expires_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL
);

CREATE INDEX selected_topic_retrieval_metrics_retention_idx
    ON selected_topic_retrieval_metrics(retention_expires_at);

CREATE TABLE generation_model_benchmarks (
    id uuid PRIMARY KEY,
    owner_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    model_id varchar(255) NOT NULL,
    artifact varchar(16) NOT NULL CHECK (artifact IN ('summary', 'mindmap', 'quiz')),
    prompt_version varchar(64) NOT NULL,
    schema_version varchar(64) NOT NULL,
    source_hash char(64) NOT NULL,
    prompt_token_count bigint CHECK (prompt_token_count IS NULL OR prompt_token_count >= 0),
    candidate_token_count bigint CHECK (candidate_token_count IS NULL OR candidate_token_count >= 0),
    total_token_count bigint CHECK (total_token_count IS NULL OR total_token_count >= 0),
    cached_content_token_count bigint CHECK (cached_content_token_count IS NULL OR cached_content_token_count >= 0),
    first_response_latency_ms bigint CHECK (first_response_latency_ms IS NULL OR first_response_latency_ms >= 0),
    provider_latency_ms bigint NOT NULL CHECK (provider_latency_ms >= 0),
    valid_output boolean NOT NULL,
    failure_code varchar(100),
    retention_expires_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL
);

CREATE INDEX generation_model_benchmarks_model_created_idx
    ON generation_model_benchmarks(model_id, created_at);
CREATE INDEX generation_model_benchmarks_retention_idx
    ON generation_model_benchmarks(retention_expires_at);

CREATE TABLE generation_model_approvals (
    model_id varchar(255) PRIMARY KEY,
    approved_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    approval_evidence_hash char(64) NOT NULL,
    CHECK (approved_at < expires_at)
);
