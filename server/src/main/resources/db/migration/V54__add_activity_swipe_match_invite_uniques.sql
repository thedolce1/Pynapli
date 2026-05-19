CREATE UNIQUE INDEX IF NOT EXISTS idx_activity_swipe_interest_unique
ON activity_events(recipient_user_id, actor_user_id, event_type, target_user_id)
WHERE event_type IN ('swipe_like', 'pineapple_like', 'intro_note') AND deleted_at IS NULL;

CREATE UNIQUE INDEX IF NOT EXISTS idx_activity_match_unique
ON activity_events(recipient_user_id, actor_user_id, event_type, target_user_id)
WHERE event_type = 'match' AND deleted_at IS NULL;

CREATE UNIQUE INDEX IF NOT EXISTS idx_activity_partner_chat_invite_unique
ON activity_events(recipient_user_id, actor_user_id, event_type, ((metadata->>'conversationId')))
WHERE event_type = 'partner_chat_invite' AND deleted_at IS NULL;
