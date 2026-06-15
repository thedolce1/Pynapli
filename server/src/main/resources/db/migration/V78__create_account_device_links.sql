-- Durable device -> account ledger for account-farm detection (TRUST_AND_SAFETY_SPEC).
-- The sessions table already carries a transient device_id, but revoked/expired session rows
-- disappear, so it can't show "this device created N accounts over time". This ledger persists
-- the link across the signup window.
--
-- Privacy: device_hash is a SHA-256 of the client-supplied device id computed server-side, so the
-- raw device identifier is never stored durably (mirrors hashed email/token storage). Rows cascade
-- on user deletion so erasure stays complete.
CREATE TABLE IF NOT EXISTS account_device_links (
    device_hash TEXT NOT NULL,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (device_hash, user_id)
);

-- Supports the windowed "distinct accounts for this device since T" count.
CREATE INDEX IF NOT EXISTS idx_account_device_links_hash_created
ON account_device_links (device_hash, created_at);
