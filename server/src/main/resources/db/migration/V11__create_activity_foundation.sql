CREATE TABLE IF NOT EXISTS activity_events (
    id UUID PRIMARY KEY,
    recipient_user_id UUID NOT NULL REFERENCES users(id),
    actor_user_id UUID NOT NULL REFERENCES users(id),
    event_type TEXT NOT NULL,
    target_user_id UUID REFERENCES users(id),
    aggregate_count INTEGER NOT NULL DEFAULT 1,
    first_occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_counted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    visibility_status TEXT NOT NULL DEFAULT 'visible',
    deleted_at TIMESTAMPTZ,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    CONSTRAINT activity_events_type_check CHECK (
        event_type IN (
            'profile_view',
            'flirt',
            'swipe_like',
            'pineapple_like',
            'intro_note',
            'match',
            'album_request',
            'partner_chat_invite',
            'psync_invite',
            'playing_tonight'
        )
    ),
    CONSTRAINT activity_events_count_check CHECK (aggregate_count >= 1),
    CONSTRAINT activity_events_visibility_check CHECK (visibility_status IN ('visible', 'hidden'))
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_activity_profile_view_aggregate
ON activity_events(recipient_user_id, actor_user_id, event_type, target_user_id)
WHERE event_type = 'profile_view' AND deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_activity_recipient_timeline
ON activity_events(recipient_user_id, occurred_at DESC, id DESC)
WHERE deleted_at IS NULL AND visibility_status = 'visible';

CREATE INDEX IF NOT EXISTS idx_activity_actor_recent
ON activity_events(actor_user_id, occurred_at DESC)
WHERE deleted_at IS NULL;
