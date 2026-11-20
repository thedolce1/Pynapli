-- "Met in person" (TRUST_AND_SAFETY_SPEC "Met in person"). A member who has met someone privately
-- confirms it; a weekly batch turns enough independent confirmations into a badge.
--
-- Privacy: `vouches` holds no user, conversation or link id. Every reference is an HMAC-SHA256 token
-- under the server key K3 (PYNAPLI_MET_IN_PERSON_TOKEN_SECRET), one domain prefix per kind, so a copy
-- of the database without the key shows only counts and days. `pair_token` is DIRECTED (voucher ->
-- subject) and unique: one row per ordered pair, ever. Dates are whole UTC days. Finding a person's
-- rows needs the key and a candidate set (their conversation partners), see MetInPersonResolution.

-- Opt-in, off by default, next to the other profile visibility settings.
ALTER TABLE discovery_settings
    ADD COLUMN IF NOT EXISTS show_met_in_person_badge BOOLEAN NOT NULL DEFAULT false;

-- One row: an HMAC of a constant under the key in use. Writes and the batch refuse to run when the
-- configured key does not reproduce it, so a lost or rotated key cannot silently reset caps or turn
-- every badge off. Rotation means re-tokenising every row in a batch (not built).
CREATE TABLE IF NOT EXISTS met_in_person_key (
    id SMALLINT PRIMARY KEY,
    key_version SMALLINT NOT NULL,
    check_value TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT met_in_person_key_single_row CHECK (id = 1)
);

CREATE TABLE IF NOT EXISTS vouches (
    id UUID PRIMARY KEY,
    pair_token TEXT NOT NULL,
    subject_token TEXT NOT NULL,
    -- Set when the voucher confirmed the subject's linked partner in the same action: the partner and
    -- the couple's canonical link row, both as tokens.
    partner_token TEXT,
    link_token TEXT,
    -- The day the pair was first given (never moves; the clique rule reads it) and the day of the
    -- standing confirmation (moves when given again after the 180-day wait).
    first_given_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    withdrawn_at TIMESTAMPTZ,
    -- Admin void: never counts again until un-voided. Invisible to the voucher.
    voided_at TIMESTAMPTZ,
    key_version SMALLINT NOT NULL DEFAULT 1,
    CONSTRAINT vouches_pair_token_unique UNIQUE (pair_token)
);

CREATE INDEX IF NOT EXISTS idx_vouches_subject_token ON vouches (subject_token);

CREATE INDEX IF NOT EXISTS idx_vouches_partner_token ON vouches (partner_token) WHERE partner_token IS NOT NULL;

-- The published badges only, keyed by user because the profile reads them. A row exists only while a
-- badge is on or the subject is frozen, so an all-false row never says "someone once confirmed this
-- person". Written by the weekly batch, an admin freeze, and an unlink (which turns the couple badge off).
CREATE TABLE IF NOT EXISTS vouch_badges (
    subject_user_id UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    person_badge BOOLEAN NOT NULL DEFAULT false,
    couple_badge BOOLEAN NOT NULL DEFAULT false,
    couple_link_id UUID REFERENCES user_profile_links(id) ON DELETE SET NULL,
    -- Database time the couple badge was published; a link ended at or after it hides the badge.
    couple_since TIMESTAMPTZ,
    frozen_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL
);

-- Hysteresis between batches (pending crossings), keyed by the subject's token, never by user id.
CREATE TABLE IF NOT EXISTS vouch_badge_states (
    subject_token TEXT PRIMARY KEY,
    person_target BOOLEAN,
    person_remaining SMALLINT,
    person_below SMALLINT NOT NULL DEFAULT 0,
    couple_target BOOLEAN,
    couple_remaining SMALLINT,
    couple_below SMALLINT NOT NULL DEFAULT 0,
    couple_link_token TEXT,
    key_version SMALLINT NOT NULL DEFAULT 1
);

-- When a partner link last ended. Link rows are reused on relink, so couple confirmations given
-- before the latest end never count, and a couple badge published before it never shows.
CREATE TABLE IF NOT EXISTS vouch_link_ends (
    link_id UUID PRIMARY KEY REFERENCES user_profile_links(id) ON DELETE CASCADE,
    ended_at TIMESTAMPTZ NOT NULL
);

-- One row: when the next weekly recompute is due. Claimed and advanced in its own transaction.
CREATE TABLE IF NOT EXISTS vouch_batch_schedule (
    id SMALLINT PRIMARY KEY,
    next_run_at TIMESTAMPTZ NOT NULL,
    last_run_at TIMESTAMPTZ,
    CONSTRAINT vouch_batch_schedule_single_row CHECK (id = 1)
);
