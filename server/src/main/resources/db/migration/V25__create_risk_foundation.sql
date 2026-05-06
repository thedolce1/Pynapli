CREATE TABLE IF NOT EXISTS account_risk_states (
    user_id UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    score INTEGER NOT NULL DEFAULT 0,
    tier TEXT NOT NULL DEFAULT 'clean',
    last_event_at TIMESTAMPTZ,
    last_reviewed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT account_risk_states_score_check CHECK (score >= 0 AND score <= 100),
    CONSTRAINT account_risk_states_tier_check CHECK (
        tier IN ('clean', 'watch', 'friction', 'quarantine', 'terminate')
    )
);

CREATE INDEX IF NOT EXISTS idx_account_risk_states_tier
ON account_risk_states(tier, updated_at DESC);

CREATE TABLE IF NOT EXISTS account_risk_events (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    actor_user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    source TEXT NOT NULL,
    reason TEXT NOT NULL,
    delta INTEGER NOT NULL,
    score_after INTEGER NOT NULL,
    tier_after TEXT NOT NULL,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT account_risk_events_score_after_check CHECK (score_after >= 0 AND score_after <= 100),
    CONSTRAINT account_risk_events_tier_after_check CHECK (
        tier_after IN ('clean', 'watch', 'friction', 'quarantine', 'terminate')
    )
);

CREATE INDEX IF NOT EXISTS idx_account_risk_events_user_recent
ON account_risk_events(user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_account_risk_events_source_recent
ON account_risk_events(source, created_at DESC);
