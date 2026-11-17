-- Psync auto-pause (PSYNC_SPEC "If one partner disconnects: session pauses, state is preserved,
-- partner can resume later"). Set when the server pauses a session because this participant's
-- Psync connection dropped; the session resumes on its own when they reconnect. A manual pause or
-- resume (any status change) clears it.
ALTER TABLE psync_sessions
    ADD COLUMN IF NOT EXISTS auto_paused_user_id UUID REFERENCES users(id) ON DELETE SET NULL;
