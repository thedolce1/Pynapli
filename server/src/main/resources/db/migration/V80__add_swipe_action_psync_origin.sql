-- A like made inside a Psync session used to stay in psync_session_actions, where the liked person
-- could never see it. When a session completes, each partner's like is now also written as a normal
-- swipe action, so it reaches the target's Likes You and can be liked back. This column records which
-- session a swipe action came from (NULL for likes made in the ordinary Swipe deck).
ALTER TABLE swipe_actions
    ADD COLUMN IF NOT EXISTS psync_session_id UUID REFERENCES psync_sessions(id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS idx_swipe_actions_psync_session
    ON swipe_actions(psync_session_id)
    WHERE psync_session_id IS NOT NULL;
