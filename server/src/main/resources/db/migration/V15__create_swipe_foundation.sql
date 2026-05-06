CREATE TABLE IF NOT EXISTS swipe_actions (
    id UUID PRIMARY KEY,
    actor_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    target_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    action TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (actor_user_id, target_user_id),
    CONSTRAINT swipe_actions_action_check CHECK (action IN ('like', 'pass')),
    CONSTRAINT swipe_actions_not_self_check CHECK (actor_user_id <> target_user_id)
);

CREATE INDEX IF NOT EXISTS idx_swipe_actions_actor_recent
ON swipe_actions(actor_user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_swipe_actions_target_likes
ON swipe_actions(target_user_id, actor_user_id)
WHERE action = 'like';

CREATE TABLE IF NOT EXISTS matches (
    id UUID PRIMARY KEY,
    user_a UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    user_b UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT matches_canonical_order_check CHECK (user_a < user_b),
    UNIQUE (user_a, user_b)
);

CREATE INDEX IF NOT EXISTS idx_matches_user_a_recent
ON matches(user_a, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_matches_user_b_recent
ON matches(user_b, created_at DESC);
