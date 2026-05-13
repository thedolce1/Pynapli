ALTER TABLE users
    ADD COLUMN IF NOT EXISTS adult_confirmed_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS terms_accepted_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS terms_version TEXT,
    ADD COLUMN IF NOT EXISTS privacy_accepted_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS privacy_version TEXT;

ALTER TABLE users
    ADD CONSTRAINT users_terms_version_length_check
        CHECK (terms_version IS NULL OR char_length(terms_version) <= 40),
    ADD CONSTRAINT users_privacy_version_length_check
        CHECK (privacy_version IS NULL OR char_length(privacy_version) <= 40);

CREATE INDEX IF NOT EXISTS idx_users_adult_confirmed ON users(adult_confirmed_at)
    WHERE adult_confirmed_at IS NOT NULL;
