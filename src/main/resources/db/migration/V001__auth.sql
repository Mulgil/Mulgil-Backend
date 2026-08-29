CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE users (
    id uuid PRIMARY KEY,
    provider varchar(32) NOT NULL CHECK (provider = 'google'),
    provider_subject varchar(255) NOT NULL,
    email varchar(320) NOT NULL,
    display_name varchar(255) NOT NULL,
    created_at timestamptz NOT NULL,
    UNIQUE (provider, provider_subject)
);

CREATE TABLE auth_refresh_tokens (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash char(64) NOT NULL UNIQUE,
    family_id uuid NOT NULL,
    expires_at timestamptz NOT NULL,
    revoked_at timestamptz,
    created_at timestamptz NOT NULL
);

CREATE INDEX auth_refresh_tokens_family_idx ON auth_refresh_tokens(family_id);
