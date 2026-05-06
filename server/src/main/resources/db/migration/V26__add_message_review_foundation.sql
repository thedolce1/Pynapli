ALTER TABLE messages
ADD COLUMN IF NOT EXISTS body_fingerprint TEXT,
ADD COLUMN IF NOT EXISTS safety_reason TEXT,
ADD COLUMN IF NOT EXISTS reviewed_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_messages_sender_fingerprint_recent
ON messages(sender_user_id, body_fingerprint, created_at DESC)
WHERE body_fingerprint IS NOT NULL
  AND deleted_at IS NULL;

CREATE TABLE IF NOT EXISTS message_review_queue (
    id UUID PRIMARY KEY,
    message_id UUID NOT NULL UNIQUE REFERENCES messages(id) ON DELETE CASCADE,
    sender_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    conversation_id UUID NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    reason TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'open',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    reviewed_at TIMESTAMPTZ,
    CONSTRAINT message_review_queue_reason_check CHECK (
        reason IN ('duplicate_first_message', 'link_first_message')
    ),
    CONSTRAINT message_review_queue_status_check CHECK (
        status IN ('open', 'released', 'removed', 'dismissed')
    )
);

CREATE INDEX IF NOT EXISTS idx_message_review_queue_open
ON message_review_queue(status, created_at ASC)
WHERE status = 'open';

CREATE INDEX IF NOT EXISTS idx_message_review_queue_sender_recent
ON message_review_queue(sender_user_id, created_at DESC);
