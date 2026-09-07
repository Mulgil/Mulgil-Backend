CREATE TABLE generation_context_caches (
    id uuid PRIMARY KEY,
    owner_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    snapshot_hash char(64) NOT NULL CHECK (snapshot_hash ~ '^[0-9a-f]{64}$'),
    provider varchar(32) NOT NULL,
    model_id varchar(128) NOT NULL,
    location varchar(64) NOT NULL,
    input_contract varchar(64) NOT NULL,
    status varchar(16) NOT NULL CHECK (status IN ('creating','active','failed')),
    token_count bigint CHECK (token_count IS NULL OR token_count >= 0),
    error_code varchar(100) CHECK (error_code IS NULL OR error_code ~ '^[A-Z0-9_]+$'),
    generation integer NOT NULL DEFAULT 1 CHECK (generation > 0),
    expires_at timestamptz NOT NULL,
    last_used_at timestamptz,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    UNIQUE (owner_id,snapshot_hash,provider,model_id,location,input_contract)
);

CREATE INDEX generation_context_caches_expiry_idx
    ON generation_context_caches (expires_at) WHERE status = 'active';

ALTER TABLE ai_provider_usage
    ADD COLUMN context_cache_status varchar(16)
        CHECK (context_cache_status IS NULL OR context_cache_status IN ('disabled','hit','miss','error')),
    ADD COLUMN context_cache_token_count bigint
        CHECK (context_cache_token_count IS NULL OR context_cache_token_count >= 0);
