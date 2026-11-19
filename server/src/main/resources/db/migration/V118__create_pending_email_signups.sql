-- Email sign-up verifies ownership of the address before any account exists (ONBOARDING_AUTH_SPEC,
-- "Membership privacy"). A sign-up request for an address with no account parks what is needed to
-- finish here; the account is created from this row, in one transaction, only when the emailed code
-- comes back. A sign-up for an address that already has an account writes nothing here.
--
-- Kept deliberately small: no plaintext email (only the same SHA-256 hash users.email_hash holds),
-- no location, no profile, no device labels. Rows expire after 24 hours and are deleted on the next
-- sign-up write and by the data-retention job. A pending row can't sign in and nothing reads it but
-- the sign-up service.
CREATE TABLE IF NOT EXISTS pending_email_signups (
    id UUID PRIMARY KEY,
    email_hash TEXT NOT NULL,
    -- SHA-256 of the random token the sign-up response handed to the device that started it. Verify
    -- and resend must present it, so someone who only knows the address can't finish (or re-code)
    -- another person's sign-up.
    signup_token_hash TEXT NOT NULL,
    password_hash TEXT NOT NULL,
    password_salt TEXT NOT NULL,
    password_iterations INTEGER NOT NULL,
    birthdate DATE NOT NULL,
    -- When the 18+ confirmation, Terms and Privacy Policy were accepted, and which versions.
    accepted_at TIMESTAMPTZ NOT NULL,
    terms_version TEXT NOT NULL,
    privacy_version TEXT NOT NULL,
    -- SHA-256 of "<id>:<6-digit code>"; a resend replaces it and resets the attempt count.
    code_hash TEXT NOT NULL,
    code_expires_at TIMESTAMPTZ NOT NULL,
    failed_attempts INTEGER NOT NULL DEFAULT 0,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- One pending sign-up per address: a new sign-up replaces the old row (and so its code).
    CONSTRAINT pending_email_signups_email_hash_key UNIQUE (email_hash)
);

CREATE INDEX IF NOT EXISTS idx_pending_email_signups_expires_at ON pending_email_signups (expires_at);

-- Set when the account was created through a verified email (the code flow above). Existing accounts
-- stay NULL; nothing is gated on it yet.
ALTER TABLE users ADD COLUMN IF NOT EXISTS email_verified_at TIMESTAMPTZ;
