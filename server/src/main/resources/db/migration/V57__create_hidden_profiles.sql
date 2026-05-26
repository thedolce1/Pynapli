CREATE TABLE IF NOT EXISTS user_hidden_profiles (
    id UUID PRIMARY KEY,
    hider_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    hidden_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (hider_user_id, hidden_user_id),
    CONSTRAINT user_hidden_profiles_not_self_check CHECK (hider_user_id <> hidden_user_id)
);

CREATE INDEX IF NOT EXISTS idx_user_hidden_profiles_hider
ON user_hidden_profiles(hider_user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_user_hidden_profiles_hidden
ON user_hidden_profiles(hidden_user_id);
