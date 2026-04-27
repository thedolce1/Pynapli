CREATE UNIQUE INDEX IF NOT EXISTS idx_activity_flirt_aggregate
ON activity_events(recipient_user_id, actor_user_id, event_type, target_user_id)
WHERE event_type = 'flirt' AND deleted_at IS NULL;
