-- "Met in person" (TRUST_AND_SAFETY_SPEC "Met in person"). A member who has met someone privately
-- confirms it; a weekly batch turns enough independent confirmations into a badge.
--
-- Privacy: the voucher is never stored in clear. voucher_token, pair_token and conversation_token
-- are HMAC-SHA256 values under the server secret PYNAPLI_MET_IN_PERSON_TOKEN_SECRET (K3), so this
-- table alone does not say who confirmed whom. pair_token is DIRECTED (voucher -> subject), so A
-- confirming B and B confirming A are two rows. The conversation is tokenised too: a clear
-- conversation id would name the voucher through direct_conversations. Dates are stored at day
-- precision (the service truncates them) so a timestamp cannot be lined up with a message or session.

-- Opt-in, off by default, next to the other profile visibility settings.
ALTER TABLE discovery_settings
    ADD COLUMN IF NOT EXISTS show_met_in_person_badge BOOLEAN NOT NULL DEFAULT false;

CREATE TABLE IF NOT EXISTS vouches (
    id UUID PRIMARY KEY,
    subject_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    voucher_token TEXT NOT NULL,
    pair_token TEXT NOT NULL,
    -- Set when the voucher also confirmed the subject's linked partner in the same action; link_id is
    -- the canonical user_profile_links row of that couple. Unlinking clears link_id (couple credit is
    -- void; personal credit stays).
    met_partner_user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    link_id UUID REFERENCES user_profile_links(id) ON DELETE SET NULL,
    conversation_token TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    withdrawn_at TIMESTAMPTZ,
    -- Admin void: never counts again. Invisible to the voucher, whose view of the row is unchanged.
    voided_at TIMESTAMPTZ,
    CONSTRAINT vouches_pair_token_unique UNIQUE (pair_token)
);

CREATE INDEX IF NOT EXISTS idx_vouches_voucher_token_created
ON vouches (voucher_token, created_at);

CREATE INDEX IF NOT EXISTS idx_vouches_subject
ON vouches (subject_user_id);

CREATE INDEX IF NOT EXISTS idx_vouches_link
ON vouches (link_id)
WHERE link_id IS NOT NULL;

-- Written only by the weekly batch (and an admin freeze). Never read by any client except as the two
-- booleans on the profile, gated by the opt-in. computed_at is never exposed.
CREATE TABLE IF NOT EXISTS vouch_badges (
    subject_user_id UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    person_badge BOOLEAN NOT NULL DEFAULT false,
    couple_badge BOOLEAN NOT NULL DEFAULT false,
    couple_link_id UUID REFERENCES user_profile_links(id) ON DELETE SET NULL,
    computed_at TIMESTAMPTZ NOT NULL,
    -- Admin freeze: both badges stay off until unfrozen, whatever the batch computes.
    frozen_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_vouch_badges_couple_link
ON vouch_badges (couple_link_id)
WHERE couple_link_id IS NOT NULL;

-- One row: when the next weekly recompute is due. Persisted so a deploy does not re-roll the random
-- time, and checked/advanced in the same transaction as the badge rewrite, so it runs once per week.
-- The service creates the row on first use.
CREATE TABLE IF NOT EXISTS vouch_batch_schedule (
    id SMALLINT PRIMARY KEY,
    next_run_at TIMESTAMPTZ NOT NULL,
    last_run_at TIMESTAMPTZ,
    CONSTRAINT vouch_batch_schedule_single_row CHECK (id = 1)
);
