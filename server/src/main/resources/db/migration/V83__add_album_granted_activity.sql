-- ACTIVITY_SPEC "Private Album Grant": created when album access is granted. Approving a private
-- album request recorded nothing for the requester, so they never learned they now had access.
-- One live card per owner -> requester; a later re-grant re-surfaces it.

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
        'album_granted',
        'partner_chat_invite',
        'psync_invite',
        'psync_started',
        'psync_recap',
        'psync_expired',
        'playing_tonight'
    )
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_activity_album_granted_unique
ON activity_events(recipient_user_id, actor_user_id)
WHERE event_type = 'album_granted' AND deleted_at IS NULL;
