CREATE TABLE IF NOT EXISTS boost_activations (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    boost_type TEXT NOT NULL,
    source TEXT NOT NULL,
    starts_at TIMESTAMPTZ NOT NULL,
    ends_at TIMESTAMPTZ NOT NULL,
    status TEXT NOT NULL DEFAULT 'active',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT boost_activations_type_check CHECK (boost_type IN ('boost', 'super_boost')),
    CONSTRAINT boost_activations_source_check CHECK (source IN ('inventory', 'admin', 'promo', 'dev')),
    CONSTRAINT boost_activations_status_check CHECK (status IN ('active', 'expired', 'canceled')),
    CONSTRAINT boost_activations_period_check CHECK (ends_at > starts_at)
);

CREATE INDEX IF NOT EXISTS idx_boost_activations_user_active
ON boost_activations(user_id, ends_at DESC)
WHERE status = 'active';

CREATE INDEX IF NOT EXISTS idx_boost_activations_active_discovery
ON boost_activations(ends_at DESC, user_id)
WHERE status = 'active';
