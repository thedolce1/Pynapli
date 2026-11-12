-- Forgot-password codes. Only a hash of the 6-digit code is stored; a code is single-use, expires,
-- and stops working after a few wrong guesses. Requesting a new code retires the older ones.
CREATE TABLE IF NOT EXISTS password_reset_codes (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    code_hash TEXT NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    failed_attempts INTEGER NOT NULL DEFAULT 0,
    consumed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_password_reset_codes_live
    ON password_reset_codes (user_id, created_at DESC)
    WHERE consumed_at IS NULL;
