CREATE TABLE IF NOT EXISTS partner_chat_invites (
    id UUID PRIMARY KEY,
    thread_id UUID NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    inviter_user_id UUID NOT NULL REFERENCES users(id),
    invited_partner_user_id UUID NOT NULL REFERENCES users(id),
    third_party_status TEXT NOT NULL DEFAULT 'pending',
    partner_status TEXT NOT NULL DEFAULT 'pending',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at TIMESTAMPTZ,
    CONSTRAINT partner_chat_invites_status_check CHECK (
        third_party_status IN ('pending', 'accepted', 'declined') AND
        partner_status IN ('pending', 'accepted', 'declined')
    ),
    CONSTRAINT partner_chat_invites_distinct_users_check CHECK (
        inviter_user_id <> invited_partner_user_id
    )
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_partner_chat_invites_open_thread_partner
ON partner_chat_invites(thread_id, invited_partner_user_id)
WHERE resolved_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_partner_chat_invites_thread_open
ON partner_chat_invites(thread_id, created_at DESC)
WHERE resolved_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_partner_chat_invites_cooldown
ON partner_chat_invites(thread_id, invited_partner_user_id, resolved_at DESC)
WHERE third_party_status = 'declined';
