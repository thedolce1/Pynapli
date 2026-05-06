ALTER TABLE notification_outbox
ADD COLUMN IF NOT EXISTS throttle_key TEXT;

CREATE INDEX IF NOT EXISTS idx_notification_outbox_throttle_recent
ON notification_outbox(recipient_user_id, kind, throttle_key, created_at DESC)
WHERE throttle_key IS NOT NULL
  AND status <> 'failed';
