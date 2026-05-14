ALTER TABLE swipe_actions
ADD COLUMN IF NOT EXISTS rewound_at TIMESTAMPTZ;

ALTER TABLE swipe_actions
DROP CONSTRAINT IF EXISTS swipe_actions_actor_user_id_target_user_id_key;

CREATE UNIQUE INDEX IF NOT EXISTS idx_swipe_actions_actor_target_active_unique
ON swipe_actions(actor_user_id, target_user_id)
WHERE rewound_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_swipe_actions_actor_active_recent
ON swipe_actions(actor_user_id, created_at DESC, id DESC)
WHERE rewound_at IS NULL;

DROP INDEX IF EXISTS idx_swipe_actions_target_likes;

CREATE INDEX IF NOT EXISTS idx_swipe_actions_target_likes
ON swipe_actions(target_user_id, actor_user_id)
WHERE action IN ('like', 'pineapple_like')
  AND rewound_at IS NULL;
