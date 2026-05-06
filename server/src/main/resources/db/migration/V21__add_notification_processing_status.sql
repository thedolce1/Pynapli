ALTER TABLE notification_outbox
DROP CONSTRAINT IF EXISTS notification_outbox_status_check;

ALTER TABLE notification_outbox
ADD CONSTRAINT notification_outbox_status_check CHECK (
    status IN ('pending', 'processing', 'sent', 'skipped', 'failed')
);

CREATE INDEX IF NOT EXISTS idx_notification_outbox_processing
ON notification_outbox(updated_at ASC)
WHERE status = 'processing';
