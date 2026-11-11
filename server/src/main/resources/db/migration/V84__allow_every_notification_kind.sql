-- The outbox CHECK constraint was last updated in V24, but the server has sent more kinds since:
-- tonight_invite and tonight_reminder (Playing Tonight), psync_invite, tonight_accepted /
-- tonight_declined / tonight_ended, and album_granted. The outbox row is only written when the
-- recipient has a registered push device, so in tests and device-less dev runs nothing failed, but
-- for a real user with a phone each of these raised a constraint violation and failed the request
-- that triggered it (proposing a together Tonight session, creating a Psync session, approving an
-- album request, ...). This lists every kind in NotificationServiceConstants.NOTIFICATION_KINDS;
-- NotificationOutboxKindsIntegrationTest fails if the two ever drift apart again.

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
        'album_granted',
        'profile_link_invite',
        'profile_link_accepted',
        'partner_chat_invite',
        'tonight_invite',
        'tonight_reminder',
        'tonight_accepted',
        'tonight_declined',
        'tonight_ended',
        'psync_invite',
        'system_safety'
    )
);
