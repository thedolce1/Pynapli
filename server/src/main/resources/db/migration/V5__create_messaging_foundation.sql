CREATE TABLE IF NOT EXISTS conversations (
    id UUID PRIMARY KEY,
    kind TEXT NOT NULL,
    origin TEXT NOT NULL,
    created_by_user_id UUID NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT conversations_kind_check CHECK (kind IN ('direct')),
    CONSTRAINT conversations_origin_check CHECK (origin IN ('grid', 'swipe', 'intro_note'))
);

CREATE TABLE IF NOT EXISTS direct_conversations (
    conversation_id UUID PRIMARY KEY REFERENCES conversations(id) ON DELETE CASCADE,
    user_a_id UUID NOT NULL REFERENCES users(id),
    user_b_id UUID NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT direct_conversations_distinct_users_check CHECK (user_a_id <> user_b_id)
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_direct_conversations_pair
ON direct_conversations (LEAST(user_a_id, user_b_id), GREATEST(user_a_id, user_b_id));

CREATE INDEX IF NOT EXISTS idx_direct_conversations_user_a ON direct_conversations(user_a_id);
CREATE INDEX IF NOT EXISTS idx_direct_conversations_user_b ON direct_conversations(user_b_id);

CREATE TABLE IF NOT EXISTS conversation_participants (
    conversation_id UUID NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id),
    role TEXT NOT NULL DEFAULT 'member',
    joined_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    left_at TIMESTAMPTZ,
    PRIMARY KEY (conversation_id, user_id),
    CONSTRAINT conversation_participants_role_check CHECK (role IN ('member'))
);

CREATE INDEX IF NOT EXISTS idx_conversation_participants_user_id
ON conversation_participants(user_id)
WHERE left_at IS NULL;

CREATE TABLE IF NOT EXISTS messages (
    id UUID PRIMARY KEY,
    conversation_id UUID NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    sender_user_id UUID NOT NULL REFERENCES users(id),
    body TEXT NOT NULL,
    moderation_status TEXT NOT NULL DEFAULT 'visible',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ,
    CONSTRAINT messages_body_length_check CHECK (char_length(body) BETWEEN 1 AND 2000),
    CONSTRAINT messages_moderation_status_check CHECK (moderation_status IN ('visible', 'held', 'removed'))
);

CREATE INDEX IF NOT EXISTS idx_messages_conversation_created
ON messages(conversation_id, created_at DESC);
