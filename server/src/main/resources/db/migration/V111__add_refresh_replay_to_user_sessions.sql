-- Idempotent refresh: for a short grace after a rotation, the refresh token that rotation consumed
-- may be presented again and gets back the SAME tokens the rotation issued (a lost response, or two
-- tabs racing), instead of being rejected and signing the user out.
--
-- The server stores only token hashes, so the issued tokens are kept sealed: AES-256-GCM under a key
-- derived from the consumed refresh token, which the server never stores. At rest the blob is useless
-- without that token. Each rotation overwrites all four columns, so only the single most recent
-- previous token is ever honoured; revocation clears them, and the replay lookup also requires
-- revoked_at IS NULL.
ALTER TABLE user_sessions
    ADD COLUMN IF NOT EXISTS previous_refresh_token_hash TEXT,
    ADD COLUMN IF NOT EXISTS replay_nonce BYTEA,
    ADD COLUMN IF NOT EXISTS replay_ciphertext BYTEA,
    ADD COLUMN IF NOT EXISTS replay_expires_at TIMESTAMPTZ;

-- Looked up by equality on the presented token's hash, like refresh_token_hash; most rows never have
-- a previous token until their first rotation, so index only the rows that do.
CREATE INDEX IF NOT EXISTS idx_user_sessions_previous_refresh_token_hash
    ON user_sessions(previous_refresh_token_hash)
    WHERE previous_refresh_token_hash IS NOT NULL;
