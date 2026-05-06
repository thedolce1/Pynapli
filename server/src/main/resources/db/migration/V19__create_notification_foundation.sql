CREATE TABLE IF NOT EXISTS notification_preferences (
    user_id UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    detail_level TEXT NOT NULL DEFAULT 'discreet',
    messages_enabled BOOLEAN NOT NULL DEFAULT true,
    matches_enabled BOOLEAN NOT NULL DEFAULT true,
    activity_enabled BOOLEAN NOT NULL DEFAULT true,
    flirts_enabled BOOLEAN NOT NULL DEFAULT true,
    album_requests_enabled BOOLEAN NOT NULL DEFAULT true,
    partner_invites_enabled BOOLEAN NOT NULL DEFAULT true,
    system_safety_enabled BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT notification_preferences_detail_level_check CHECK (detail_level IN ('discreet', 'standard'))
);

INSERT INTO notification_preferences (user_id)
SELECT id
FROM users
WHERE deleted_at IS NULL
ON CONFLICT (user_id) DO NOTHING;

CREATE TABLE IF NOT EXISTS push_devices (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    platform TEXT NOT NULL,
    device_token TEXT NOT NULL,
    app_version TEXT,
    locale TEXT,
    timezone TEXT,
    notifications_enabled BOOLEAN NOT NULL DEFAULT true,
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    disabled_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT push_devices_platform_check CHECK (platform IN ('android', 'ios')),
    CONSTRAINT push_devices_token_length_check CHECK (char_length(device_token) BETWEEN 16 AND 4096),
    UNIQUE (platform, device_token)
);

CREATE INDEX IF NOT EXISTS idx_push_devices_user_active
ON push_devices(user_id, platform, last_seen_at DESC)
WHERE disabled_at IS NULL;
