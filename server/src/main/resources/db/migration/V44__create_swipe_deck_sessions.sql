CREATE TABLE IF NOT EXISTS swipe_deck_sessions (
    id UUID PRIMARY KEY,
    deck_session_token TEXT NOT NULL,
    deck_session_token_hash TEXT NOT NULL UNIQUE,
    viewer_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    scope_hash TEXT NOT NULL,
    radius_meters INTEGER NOT NULL,
    page_limit INTEGER NOT NULL,
    filter_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    generated_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT swipe_deck_sessions_radius_check CHECK (radius_meters BETWEEN 1000 AND 100000),
    CONSTRAINT swipe_deck_sessions_limit_check CHECK (page_limit BETWEEN 1 AND 60),
    CONSTRAINT swipe_deck_sessions_period_check CHECK (expires_at > generated_at)
);

CREATE INDEX IF NOT EXISTS idx_swipe_deck_sessions_viewer_active
ON swipe_deck_sessions(viewer_user_id, expires_at DESC, generated_at DESC);

CREATE TABLE IF NOT EXISTS swipe_deck_cards (
    id UUID PRIMARY KEY,
    deck_session_id UUID NOT NULL REFERENCES swipe_deck_sessions(id) ON DELETE CASCADE,
    viewer_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    target_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    card_token_hash TEXT NOT NULL,
    position INTEGER NOT NULL,
    dealt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    action TEXT,
    action_recorded_at TIMESTAMPTZ,
    CONSTRAINT swipe_deck_cards_position_check CHECK (position >= 0),
    CONSTRAINT swipe_deck_cards_action_check CHECK (
        action IS NULL OR action IN ('like', 'pass', 'pineapple_like', 'intro_note')
    )
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_swipe_deck_cards_session_target
ON swipe_deck_cards(deck_session_id, target_user_id);

CREATE UNIQUE INDEX IF NOT EXISTS idx_swipe_deck_cards_session_token
ON swipe_deck_cards(deck_session_id, card_token_hash);

CREATE UNIQUE INDEX IF NOT EXISTS idx_swipe_deck_cards_session_position
ON swipe_deck_cards(deck_session_id, position);

CREATE INDEX IF NOT EXISTS idx_swipe_deck_cards_viewer_target
ON swipe_deck_cards(viewer_user_id, target_user_id, dealt_at DESC);
