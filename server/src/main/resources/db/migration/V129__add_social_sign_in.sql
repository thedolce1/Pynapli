-- Continue with Google / Apple (ONBOARDING_AUTH_SPEC "Social sign-in", API_CONTRACT_SPEC "Section 5
-- contract"). Social identities live in auth_identities next to the email identity, keyed by the
-- provider's stable subject (Google/Apple `sub`), never by email.
--
-- Stored no more than needed:
--   provider_email_hint  a masked address for Settings ("j•••@gmail.com"). The full address is never
--                        stored here; when it is a provider-verified, non-relay address its unsalted
--                        SHA-256 goes to users.email_hash like an email account's, and nowhere else.
--   email_verified       whether the provider vouched for that address at the last sign-in.
--   linked_at            when this sign-in method was added to the account.
--   apple_refresh_token_sealed  Apple's refresh token, AES-256-GCM under a key derived from
--                        PYNAPLI_SOCIAL_TOKEN_KEY (12-byte nonce || ciphertext+tag, the identity id as
--                        associated data). Only used to revoke the grant when the account is deleted or
--                        Apple is unlinked (Apple requires that). Never exported, never logged.
--   apple_client_id      the client id (bundle id or Services ID) the token was issued to: revocation
--                        must name the same one.
ALTER TABLE auth_identities ADD COLUMN IF NOT EXISTS provider_email_hint TEXT;
ALTER TABLE auth_identities ADD COLUMN IF NOT EXISTS email_verified BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE auth_identities ADD COLUMN IF NOT EXISTS linked_at TIMESTAMPTZ;
ALTER TABLE auth_identities ADD COLUMN IF NOT EXISTS apple_refresh_token_sealed BYTEA;
ALTER TABLE auth_identities ADD COLUMN IF NOT EXISTS apple_client_id TEXT;

DO $$
DECLARE
    n integer;
BEGIN
    UPDATE auth_identities SET linked_at = created_at WHERE linked_at IS NULL;
    GET DIAGNOSTICS n = ROW_COUNT;
    RAISE NOTICE 'V129: linked_at backfilled on % identities', n;
END $$;

ALTER TABLE auth_identities ALTER COLUMN linked_at SET DEFAULT now();
ALTER TABLE auth_identities ALTER COLUMN linked_at SET NOT NULL;

-- UNIQUE (provider, provider_subject) has existed since V2: one provider identity belongs to at most
-- one account. New: an account has at most one identity per provider. Deleted accounts' identities
-- are anonymised to 'deleted:<id>' and stay (audit), so they are left out.
CREATE UNIQUE INDEX IF NOT EXISTS ux_auth_identities_user_provider
    ON auth_identities (user_id, provider)
    WHERE provider_subject NOT LIKE 'deleted:%';
