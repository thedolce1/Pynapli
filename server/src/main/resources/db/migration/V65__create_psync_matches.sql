-- PS-3: Psync match resolution + recap (PSYNC_SPEC "Match Outcomes" / "Session Recap").
-- When a Psync session finishes (both partners exhaust the shared deck, or either taps
-- finish), Ktor resolves each deck target into one of:
--   * together  -- both partners liked the target, the target is open to together-mode
--                   (discovery_settings.together_enabled) AND reciprocated (liked >=1 partner)
--   * solo      -- one partner liked the target and the target liked that partner back
--                   (standard solo passthrough match; private to that partner by default)
--   * no match  -- nothing recorded here
-- Blocks override everything: a target blocked by/Blocking either partner is skipped.
-- The reciprocal entry still lands in the canonical `matches` table; this table records
-- the Psync-session provenance + kind so the recap and (later) Activity can summarise it.

CREATE TABLE IF NOT EXISTS psync_session_matches (
    id UUID PRIMARY KEY,
    session_id UUID NOT NULL REFERENCES psync_sessions(id) ON DELETE CASCADE,
    target_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    kind TEXT NOT NULL,
    -- For 'solo' this is the partner who matched the target; NULL for 'together'
    -- (a together match belongs to both partners).
    owner_user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    -- The canonical matches.id created/kept for this outcome (SET NULL if that match is removed).
    match_id UUID REFERENCES matches(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT psync_session_matches_kind_check CHECK (kind IN ('together', 'solo')),
    -- 'together' must have no owner; 'solo' must have one.
    CONSTRAINT psync_session_matches_owner_check
        CHECK ((kind = 'together' AND owner_user_id IS NULL) OR (kind = 'solo' AND owner_user_id IS NOT NULL))
);

-- One together outcome per (session, target); one solo outcome per (session, target, owner).
CREATE UNIQUE INDEX IF NOT EXISTS uq_psync_session_matches_together
    ON psync_session_matches(session_id, target_user_id)
    WHERE kind = 'together';

CREATE UNIQUE INDEX IF NOT EXISTS uq_psync_session_matches_solo
    ON psync_session_matches(session_id, target_user_id, owner_user_id)
    WHERE kind = 'solo';

-- Recap reads everything for a session; the owner filter narrows to a viewer's own solo matches.
CREATE INDEX IF NOT EXISTS idx_psync_session_matches_session
    ON psync_session_matches(session_id);
