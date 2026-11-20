-- "Hide from people you know" (TRUST_AND_SAFETY_SPEC, "Contact blocks"). A member (the blocker) adds
-- email addresses of people who should never come across them. One row per (blocker, address).
--
-- No plaintext address and no plain users.email_hash here: email_token is HMAC-SHA256(K1, normalised
-- address) and target_token is HMAC-SHA256(K2, ...) of the member it was bound to, both keyed with
-- server secrets (PYNAPLI_CONTACT_BLOCK_EMAIL_KEY / PYNAPLI_CONTACT_BLOCK_TARGET_KEY). Without the keys
-- a copy of this table (even together with users) shows how many addresses each member added and
-- when, and that several members added the same (unknown) address, but not whose address it is nor
-- which member it points at.
--
-- target_token is NULL while the address belongs to no verified member ("pending"). The resolver sets
-- it to HMAC(K2, "target:" || member id) when it binds the row, or to HMAC(K2, "exempt:" || member id)
-- when the pair had already interacted (the exemption, decided once and kept). The two look the same
-- without K2, so a leak doesn't say which pairs are hidden and which are exempt.
CREATE TABLE IF NOT EXISTS contact_blocks (
    id UUID PRIMARY KEY,
    blocker_user_id UUID NOT NULL REFERENCES users(id),
    email_token TEXT NOT NULL,
    target_token TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT contact_blocks_blocker_email_key UNIQUE (blocker_user_id, email_token)
);

-- The per-viewer lookup ("who is hidden from me"): one index range per request.
CREATE INDEX IF NOT EXISTS idx_contact_blocks_target
    ON contact_blocks (target_token, blocker_user_id)
    WHERE target_token IS NOT NULL;

-- The verify/sign-in settling (pending rows of one address) and the resolver's pages.
CREATE INDEX IF NOT EXISTS idx_contact_blocks_pending
    ON contact_blocks (email_token)
    WHERE target_token IS NULL;

CREATE INDEX IF NOT EXISTS idx_contact_blocks_pending_created
    ON contact_blocks (created_at, id)
    WHERE target_token IS NULL;

-- HMAC(K2, 'member:' || HMAC(K1, normalised address)) of a member whose address is verified, written
-- at the moments the server holds the plaintext (sign-up verify, and every successful sign-in, which
-- covers accounts verified before this migration). Deliberately NOT equal to contact_blocks.email_token,
-- so the two tables can't be joined without K2. NULL for unverified accounts: they are never bound.
-- Cleared on deletion.
ALTER TABLE users ADD COLUMN IF NOT EXISTS contact_token TEXT;

CREATE INDEX IF NOT EXISTS idx_users_contact_token
    ON users (contact_token)
    WHERE contact_token IS NOT NULL;
