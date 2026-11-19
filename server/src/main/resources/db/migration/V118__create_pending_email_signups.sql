-- Email sign-up verifies ownership of the address before any account exists (ONBOARDING_AUTH_SPEC,
-- "Email sign-up"). A sign-up request for an address with no account parks what is needed to finish
-- here; the account is created from the row, in one transaction, only when the emailed code comes
-- back. A sign-up for an address that already has an account writes nothing here.
--
-- One row per started sign-up (per sign-up token), not per address: someone else starting a sign-up
-- for the same address can't replace or kill the owner's. At most a few live rows per address (the
-- service refuses more; it never evicts). Verify deletes every row of the address.
--
-- Kept deliberately small: no plaintext email (only the same SHA-256 hash users.email_hash holds),
-- no location, no profile, no device labels, no IP. Rows expire after 24 hours and are deleted on the
-- next sign-up write and by the data-retention job. A pending row can't sign in and nothing reads it
-- but the sign-up service.
CREATE TABLE IF NOT EXISTS pending_email_signups (
    id UUID PRIMARY KEY,
    email_hash TEXT NOT NULL,
    -- SHA-256 of the random token the sign-up response handed to the device that started it. Verify
    -- and resend must present it.
    signup_token_hash TEXT NOT NULL,
    password_hash TEXT NOT NULL,
    password_salt TEXT NOT NULL,
    password_iterations INTEGER NOT NULL,
    birthdate DATE NOT NULL,
    -- When the 18+ confirmation, Terms and Privacy Policy were accepted, and which versions.
    accepted_at TIMESTAMPTZ NOT NULL,
    terms_version TEXT NOT NULL,
    privacy_version TEXT NOT NULL,
    -- SHA-256 of "<id>:<6-digit code>"; a resend replaces it (at most a few codes per row).
    code_hash TEXT NOT NULL,
    code_expires_at TIMESTAMPTZ NOT NULL,
    codes_issued INTEGER NOT NULL DEFAULT 1,
    -- Every verify spends one attempt BEFORE the code is compared, and a resend never gives any back:
    -- this is the lifetime guess budget of the row (and so of its token).
    attempts BIGINT NOT NULL DEFAULT 0,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pending_email_signups_token_key UNIQUE (signup_token_hash)
);

CREATE INDEX IF NOT EXISTS idx_pending_email_signups_email_hash ON pending_email_signups (email_hash, expires_at);
CREATE INDEX IF NOT EXISTS idx_pending_email_signups_expires_at ON pending_email_signups (expires_at);

-- The permanent placeholder row. A verify that matches no live sign-up spends its attempt on this row
-- instead, so every failed verify writes exactly one real row whether or not the address had a
-- pending sign-up (and so whether or not it is a member). Its hashes match nothing, its code is
-- expired, and it never expires, so retention never removes it.
INSERT INTO pending_email_signups (
    id, email_hash, signup_token_hash, password_hash, password_salt, password_iterations,
    birthdate, accepted_at, terms_version, privacy_version, code_hash, code_expires_at,
    codes_issued, attempts, expires_at
)
VALUES (
    '00000000-0000-0000-0000-000000000000', 'placeholder', 'placeholder', 'placeholder', 'placeholder', 0,
    DATE '1900-01-01', TIMESTAMPTZ 'epoch', 'placeholder', 'placeholder', 'placeholder', TIMESTAMPTZ 'epoch',
    0, 0, TIMESTAMPTZ 'infinity'
)
ON CONFLICT (id) DO NOTHING;

-- Set when the account was created through a verified email (the code flow above). Existing accounts
-- stay NULL; nothing is gated on it yet.
ALTER TABLE users ADD COLUMN IF NOT EXISTS email_verified_at TIMESTAMPTZ;
