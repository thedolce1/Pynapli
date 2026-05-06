CREATE TABLE IF NOT EXISTS subscriptions (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    platform TEXT NOT NULL,
    external_subscription_id TEXT NOT NULL,
    tier TEXT NOT NULL,
    status TEXT NOT NULL,
    current_period_start TIMESTAMPTZ NOT NULL,
    current_period_end TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT subscriptions_platform_check CHECK (platform IN ('app_store', 'play_store', 'stripe', 'admin', 'dev')),
    CONSTRAINT subscriptions_tier_check CHECK (tier IN ('social', 'select')),
    CONSTRAINT subscriptions_status_check CHECK (status IN ('active', 'grace', 'canceled', 'expired', 'revoked', 'pending')),
    CONSTRAINT subscriptions_period_check CHECK (current_period_end > current_period_start),
    UNIQUE (platform, external_subscription_id)
);

CREATE INDEX IF NOT EXISTS idx_subscriptions_user_active
ON subscriptions(user_id, current_period_end DESC)
WHERE status IN ('active', 'grace');

CREATE TABLE IF NOT EXISTS entitlements (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    tier TEXT NOT NULL,
    source TEXT NOT NULL,
    source_id UUID,
    starts_at TIMESTAMPTZ NOT NULL,
    ends_at TIMESTAMPTZ,
    status TEXT NOT NULL DEFAULT 'active',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT entitlements_tier_check CHECK (tier IN ('free', 'social', 'select')),
    CONSTRAINT entitlements_source_check CHECK (source IN ('own_subscription', 'linked_partner_benefit', 'promo', 'admin')),
    CONSTRAINT entitlements_status_check CHECK (status IN ('active', 'expired', 'revoked'))
);

CREATE INDEX IF NOT EXISTS idx_entitlements_user_active
ON entitlements(user_id, starts_at DESC)
WHERE status = 'active';

CREATE TABLE IF NOT EXISTS linked_partner_benefits (
    id UUID PRIMARY KEY,
    subscriber_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    beneficiary_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    partner_link_id UUID REFERENCES user_profile_links(id) ON DELETE SET NULL,
    subscription_id UUID REFERENCES subscriptions(id) ON DELETE SET NULL,
    tier TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'active',
    starts_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    ends_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT linked_partner_benefits_not_self_check CHECK (subscriber_user_id <> beneficiary_user_id),
    CONSTRAINT linked_partner_benefits_tier_check CHECK (tier IN ('social', 'select')),
    CONSTRAINT linked_partner_benefits_status_check CHECK (status IN ('active', 'ended', 'revoked'))
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_linked_partner_benefits_active_subscriber
ON linked_partner_benefits(subscriber_user_id)
WHERE status = 'active' AND ends_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_linked_partner_benefits_beneficiary_active
ON linked_partner_benefits(beneficiary_user_id)
WHERE status = 'active' AND ends_at IS NULL;
