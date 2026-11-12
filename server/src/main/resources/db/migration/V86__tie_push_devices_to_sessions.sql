-- A push device belongs to the session that registered it. When that session ends (remote sign-out,
-- password reset, account deletion, ban), the phone stops receiving notifications even though the
-- app never got the chance to unregister. Devices registered before this keep session_id NULL and
-- keep working until the app re-registers on its next start, which fills it in.
ALTER TABLE push_devices
    ADD COLUMN IF NOT EXISTS session_id UUID REFERENCES user_sessions(id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS idx_push_devices_session_id ON push_devices (session_id);
