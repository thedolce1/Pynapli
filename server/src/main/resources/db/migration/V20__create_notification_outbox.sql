CREATE TABLE IF NOT EXISTS notification_outbox (
    id UUID PRIMARY KEY,
    recipient_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    actor_user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    kind TEXT NOT NULL,
    subject_id UUID,
    idempotency_key TEXT NOT NULL,
    title TEXT NOT NULL,
    body TEXT NOT NULL,
    payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    status TEXT NOT NULL DEFAULT 'pending',
    attempt_count INTEGER NOT NULL DEFAULT 0,
    available_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    sent_at TIMESTAMPTZ,
    failed_at TIMESTAMPTZ,
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT notification_outbox_kind_check CHECK (
        kind IN (
            'message',
            'match',
            'flirt',
            'album_request',
            'profile_link_invite',
            'profile_link_accepted',
            'partner_chat_invite',
            'system_safety'
        )
    ),
    CONSTRAINT notification_outbox_status_check CHECK (
        status IN ('pending', 'sent', 'skipped', 'failed')
    ),
    CONSTRAINT notification_outbox_attempt_count_check CHECK (attempt_count >= 0)
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_notification_outbox_idempotency
ON notification_outbox(idempotency_key);

CREATE INDEX IF NOT EXISTS idx_notification_outbox_pending
ON notification_outbox(available_at ASC, created_at ASC)
WHERE status = 'pending';

CREATE INDEX IF NOT EXISTS idx_notification_outbox_recipient_recent
ON notification_outbox(recipient_user_id, created_at DESC);
