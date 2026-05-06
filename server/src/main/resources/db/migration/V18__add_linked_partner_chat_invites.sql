ALTER TABLE conversations
DROP CONSTRAINT IF EXISTS conversations_kind_check;

ALTER TABLE conversations
ADD CONSTRAINT conversations_kind_check CHECK (kind IN ('direct', 'group'));

ALTER TABLE conversation_participants
DROP CONSTRAINT IF EXISTS conversation_participants_status_check;

ALTER TABLE conversation_participants
ADD COLUMN IF NOT EXISTS invitation_status TEXT NOT NULL DEFAULT 'active',
ADD COLUMN IF NOT EXISTS invited_by_user_id UUID REFERENCES users(id),
ADD COLUMN IF NOT EXISTS invited_at TIMESTAMPTZ,
ADD COLUMN IF NOT EXISTS invitation_responded_at TIMESTAMPTZ;

UPDATE conversation_participants
SET invitation_status = 'active'
WHERE invitation_status IS NULL;

ALTER TABLE conversation_participants
ADD CONSTRAINT conversation_participants_status_check
CHECK (invitation_status IN ('active', 'invited', 'declined', 'left'));

CREATE INDEX IF NOT EXISTS idx_conversation_participants_invited_user
ON conversation_participants(user_id, invited_at DESC)
WHERE invitation_status = 'invited' AND left_at IS NULL;
