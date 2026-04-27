ALTER TABLE conversation_participants
ADD COLUMN IF NOT EXISTS last_read_at TIMESTAMPTZ NOT NULL DEFAULT now();

CREATE INDEX IF NOT EXISTS idx_messages_unread_lookup
ON messages(conversation_id, created_at)
WHERE deleted_at IS NULL AND moderation_status = 'visible';
