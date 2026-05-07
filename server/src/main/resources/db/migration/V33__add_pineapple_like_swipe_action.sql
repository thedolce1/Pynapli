ALTER TABLE swipe_actions
DROP CONSTRAINT IF EXISTS swipe_actions_action_check;

ALTER TABLE swipe_actions
ADD CONSTRAINT swipe_actions_action_check
CHECK (action IN ('like', 'pass', 'pineapple_like', 'intro_note'));

DROP INDEX IF EXISTS idx_swipe_actions_target_likes;

CREATE INDEX IF NOT EXISTS idx_swipe_actions_target_likes
ON swipe_actions(target_user_id, actor_user_id)
WHERE action IN ('like', 'pineapple_like');
