-- PS-4c: dedicated Psync Activity feed cards (PSYNC_SPEC "Activity Integration").
-- psync_invite already existed in the V11 event_type CHECK; this adds the rest of the
-- session lifecycle (started / recap / expired) so the Activity feed can surface them.
-- Recorded once per recipient per session per type (idempotent), keyed on metadata->>'sessionId'.

ALTER TABLE activity_events DROP CONSTRAINT activity_events_type_check;

ALTER TABLE activity_events ADD CONSTRAINT activity_events_type_check CHECK (
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
        'psync_started',
        'psync_recap',
        'psync_expired',
        'playing_tonight'
    )
);

-- One card per recipient per session per psync event type.
CREATE UNIQUE INDEX IF NOT EXISTS idx_activity_psync_session_unique
ON activity_events(recipient_user_id, event_type, ((metadata->>'sessionId')))
WHERE event_type IN ('psync_invite', 'psync_started', 'psync_recap', 'psync_expired')
  AND deleted_at IS NULL;
