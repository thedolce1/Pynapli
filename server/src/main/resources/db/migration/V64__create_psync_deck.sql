-- PS-2: Psync shared bounded deck + private per-partner swipes (PSYNC_SPEC Session
-- Flow steps 4-6, Deck Rules, Privacy Rules).
--
-- The deck is SHARED: both partners receive the same ordered card list (one row per
-- card in psync_session_cards). The swipes are PRIVATE: each partner's decisions are
-- recorded in psync_session_actions and never exposed to the other partner during the
-- session. Decks are bounded (deck_size captured at PS-1) and short-lived; the card
-- snapshot is captured at generation time, so staleness is acceptable.
--
-- Match resolution, recap, Activity and WebSockets are LATER phases (PS-3/PS-4).

-- The shared ordered deck. One row per card; both partners act on the same cards.
CREATE TABLE IF NOT EXISTS psync_session_cards (
    id UUID PRIMARY KEY,
    session_id UUID NOT NULL REFERENCES psync_sessions(id) ON DELETE CASCADE,
    position INTEGER NOT NULL,
    target_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    card_token_hash TEXT NOT NULL,
    -- The SwipeCardDto captured at generation (bounded deck, short-lived; staleness ok).
    card_snapshot JSONB NOT NULL,
    dealt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT psync_session_cards_position_unique UNIQUE (session_id, position),
    CONSTRAINT psync_session_cards_target_unique UNIQUE (session_id, target_user_id),
    CONSTRAINT psync_session_cards_token_unique UNIQUE (session_id, card_token_hash)
);

-- Private per-partner swipes. One decision per card per partner (first decision wins).
CREATE TABLE IF NOT EXISTS psync_session_actions (
    id UUID PRIMARY KEY,
    session_id UUID NOT NULL REFERENCES psync_sessions(id) ON DELETE CASCADE,
    actor_user_id UUID NOT NULL REFERENCES users(id),
    target_user_id UUID NOT NULL REFERENCES users(id),
    action TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT psync_session_actions_action_check
        CHECK (action IN ('like', 'pass', 'pineapple_like')),
    CONSTRAINT psync_session_actions_decision_unique UNIQUE (session_id, actor_user_id, target_user_id)
);

-- The both-tap-Start gate: a session generates its deck + goes 'active' only when
-- every participant has started.
ALTER TABLE psync_session_participants
    ADD COLUMN IF NOT EXISTS started BOOLEAN NOT NULL DEFAULT false;

-- Deck reads order by position; the per-viewer action overlay looks up by actor.
CREATE INDEX IF NOT EXISTS idx_psync_session_cards_session_position
    ON psync_session_cards(session_id, position);
CREATE INDEX IF NOT EXISTS idx_psync_session_actions_session_actor
    ON psync_session_actions(session_id, actor_user_id);
