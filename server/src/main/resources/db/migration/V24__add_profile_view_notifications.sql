ALTER TABLE notification_outbox
DROP CONSTRAINT IF EXISTS notification_outbox_kind_check;

ALTER TABLE notification_outbox
ADD CONSTRAINT notification_outbox_kind_check CHECK (
    kind IN (
        'message',
        'match',
        'flirt',
        'profile_view',
        'album_request',
        'profile_link_invite',
        'profile_link_accepted',
        'partner_chat_invite',
        'system_safety'
    )
);
