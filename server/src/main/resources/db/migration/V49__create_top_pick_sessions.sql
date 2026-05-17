-- Top Picks daily refresh (SWIPE_IMPLEMENTATION_SPEC L293-313). One session per
-- user per refresh window; the same set is returned for repeated reads inside
-- the window so the user sees stable picks. After expires_at a fresh session
-- supersedes — old rows are kept for audit and analytics.
CREATE TABLE IF NOT EXISTS top_pick_sessions (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    -- Backing swipe_deck_sessions row used to mint/validate card tokens. Storing
    -- the id (not the token) lets cached reads re-issue raw tokens deterministically
    -- via SwipeDeckTokenCodec without persisting plaintext tokens.
    deck_session_id UUID NOT NULL REFERENCES swipe_deck_sessions(id) ON DELETE CASCADE,
    generated_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT top_pick_sessions_expires_after_generated CHECK (expires_at > generated_at)
);

-- Lookup: "give me this user's currently-active session" — picks the freshest
-- not-yet-expired row. ORDER BY generated_at DESC + LIMIT 1 in the repo query.
CREATE INDEX IF NOT EXISTS idx_top_pick_sessions_user_active
    ON top_pick_sessions(user_id, expires_at DESC, generated_at DESC);

CREATE TABLE IF NOT EXISTS top_pick_session_cards (
    id UUID PRIMARY KEY,
    session_id UUID NOT NULL REFERENCES top_pick_sessions(id) ON DELETE CASCADE,
    target_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    position INTEGER NOT NULL,
    card_token_hash TEXT NOT NULL,
    dealt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT top_pick_session_cards_position_check CHECK (position >= 0)
);

-- Unique pair so the same target can't appear twice within one curated set.
CREATE UNIQUE INDEX IF NOT EXISTS uniq_top_pick_session_cards_session_target
    ON top_pick_session_cards(session_id, target_user_id);

-- Ordered fetch of a session's cards by position.
CREATE INDEX IF NOT EXISTS idx_top_pick_session_cards_session_position
    ON top_pick_session_cards(session_id, position);
