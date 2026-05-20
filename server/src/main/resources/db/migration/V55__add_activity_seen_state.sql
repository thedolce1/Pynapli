ALTER TABLE activity_events
ADD COLUMN IF NOT EXISTS seen_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_activity_recipient_unseen_badge
ON activity_events(recipient_user_id, occurred_at DESC, id DESC)
WHERE deleted_at IS NULL
  AND visibility_status = 'visible'
  AND seen_at IS NULL
  AND event_type IN (
      'flirt',
      'swipe_like',
      'pineapple_like',
      'intro_note',
      'match',
      'album_request',
      'partner_chat_invite'
  );
