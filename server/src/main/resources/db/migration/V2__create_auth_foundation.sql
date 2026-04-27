CREATE TABLE IF NOT EXISTS users (
    id UUID PRIMARY KEY,
    email_hash TEXT UNIQUE,
    phone_hash TEXT UNIQUE,
    firebase_uid TEXT UNIQUE,
    status TEXT NOT NULL DEFAULT 'active',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ,
    CONSTRAINT users_status_check CHECK (status IN ('active', 'paused', 'suspended', 'banned', 'deleted'))
);

CREATE TABLE IF NOT EXISTS auth_identities (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id),
    provider TEXT NOT NULL,
    provider_subject TEXT NOT NULL,
    password_hash TEXT,
    password_salt TEXT,
    password_iterations INTEGER,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_used_at TIMESTAMPTZ,
    UNIQUE (provider, provider_subject)
);

CREATE INDEX IF NOT EXISTS idx_auth_identities_user_id ON auth_identities(user_id);

CREATE TABLE IF NOT EXISTS user_sessions (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id),
    device_id TEXT,
    access_token_hash TEXT NOT NULL UNIQUE,
    refresh_token_hash TEXT NOT NULL UNIQUE,
    platform TEXT,
    app_version TEXT,
    access_expires_at TIMESTAMPTZ NOT NULL,
    refresh_expires_at TIMESTAMPTZ NOT NULL,
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_user_sessions_user_id ON user_sessions(user_id);
CREATE INDEX IF NOT EXISTS idx_user_sessions_access_token_hash ON user_sessions(access_token_hash);
CREATE INDEX IF NOT EXISTS idx_user_sessions_refresh_token_hash ON user_sessions(refresh_token_hash);

