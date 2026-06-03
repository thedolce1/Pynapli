-- PT follow-up: 15-minute-before-expiry reminder for Playing Tonight sessions
-- (PLAYING_TONIGHT_SPEC: "Recommended reminder: 15 minutes before expiration").
-- reminder_sent_at is the idempotency latch: the reminder scanner atomically claims
-- due sessions (status='active' AND reminder_sent_at IS NULL), stamps this column, and
-- enqueues a tonight_reminder to each participant — so a reminder is sent at most once
-- per session even across worker ticks / multiple server instances.

ALTER TABLE tonight_sessions
    ADD COLUMN IF NOT EXISTS reminder_sent_at TIMESTAMPTZ;

-- Keeps the reminder claim ("active sessions not yet reminded") cheap.
CREATE INDEX IF NOT EXISTS idx_tonight_sessions_reminder_due
    ON tonight_sessions(expires_at)
    WHERE status = 'active' AND reminder_sent_at IS NULL;
