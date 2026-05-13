CREATE TABLE IF NOT EXISTS age_verifications (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id),
    market TEXT NOT NULL DEFAULT 'default',
    method TEXT NOT NULL,
    provider TEXT NOT NULL,
    provider_reference TEXT,
    status TEXT NOT NULL,
    requested_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    verified_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ,
    reviewed_at TIMESTAMPTZ,
    reviewer_id TEXT,
    review_reason TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT age_verifications_status_check CHECK (status IN ('pending', 'verified', 'rejected', 'expired')),
    CONSTRAINT age_verifications_method_check CHECK (method IN ('manual_review', 'provider_callback')),
    CONSTRAINT age_verifications_provider_reference_check CHECK (provider_reference IS NULL OR char_length(provider_reference) <= 200),
    CONSTRAINT age_verifications_review_reason_check CHECK (review_reason IS NULL OR char_length(review_reason) <= 500)
);

CREATE INDEX IF NOT EXISTS idx_age_verifications_user_created ON age_verifications(user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_age_verifications_status_created ON age_verifications(status, created_at DESC);
CREATE UNIQUE INDEX IF NOT EXISTS idx_age_verifications_provider_reference
    ON age_verifications(provider, provider_reference)
    WHERE provider_reference IS NOT NULL;
