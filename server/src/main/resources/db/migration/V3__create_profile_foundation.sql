CREATE TABLE IF NOT EXISTS profiles (
    user_id UUID PRIMARY KEY REFERENCES users(id),
    display_name TEXT,
    birthdate DATE,
    display_age INTEGER,
    bio TEXT,
    gender TEXT,
    orientation TEXT,
    relationship_context TEXT,
    discovery_style TEXT NOT NULL DEFAULT 'social',
    experience_level TEXT,
    profile_completeness_score INTEGER NOT NULL DEFAULT 0,
    moderation_status TEXT NOT NULL DEFAULT 'pending',
    visibility_status TEXT NOT NULL DEFAULT 'visible',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT profiles_discovery_style_check CHECK (discovery_style IN ('solo', 'together', 'both', 'social', 'ask_in_chat')),
    CONSTRAINT profiles_moderation_status_check CHECK (moderation_status IN ('pending', 'approved', 'limited', 'rejected')),
    CONSTRAINT profiles_visibility_status_check CHECK (visibility_status IN ('visible', 'paused', 'hidden')),
    CONSTRAINT profiles_completeness_check CHECK (profile_completeness_score >= 0 AND profile_completeness_score <= 100)
);

CREATE INDEX IF NOT EXISTS idx_profiles_visibility_status ON profiles(visibility_status);
CREATE INDEX IF NOT EXISTS idx_profiles_discovery_style ON profiles(discovery_style);
CREATE INDEX IF NOT EXISTS idx_profiles_gender ON profiles(gender);
CREATE INDEX IF NOT EXISTS idx_profiles_orientation ON profiles(orientation);

CREATE TABLE IF NOT EXISTS discovery_settings (
    user_id UUID PRIMARY KEY REFERENCES users(id),
    grid_visible BOOLEAN NOT NULL DEFAULT true,
    swipe_visible BOOLEAN NOT NULL DEFAULT true,
    events_visible BOOLEAN NOT NULL DEFAULT true,
    show_distance BOOLEAN NOT NULL DEFAULT true,
    show_in_viewed_lists BOOLEAN NOT NULL DEFAULT true,
    incognito BOOLEAN NOT NULL DEFAULT false,
    allow_grid_messages BOOLEAN NOT NULL DEFAULT true,
    grid_message_policy TEXT NOT NULL DEFAULT 'everyone',
    allow_flirts BOOLEAN NOT NULL DEFAULT true,
    allow_intro_notes BOOLEAN NOT NULL DEFAULT true,
    allow_album_requests BOOLEAN NOT NULL DEFAULT true,
    solo_enabled BOOLEAN NOT NULL DEFAULT true,
    together_enabled BOOLEAN NOT NULL DEFAULT false,
    social_enabled BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT discovery_settings_grid_message_policy_check CHECK (grid_message_policy IN ('everyone', 'verified_only', 'matches_only', 'no_one'))
);

CREATE INDEX IF NOT EXISTS idx_discovery_grid_visible ON discovery_settings(grid_visible);
CREATE INDEX IF NOT EXISTS idx_discovery_swipe_visible ON discovery_settings(swipe_visible);
CREATE INDEX IF NOT EXISTS idx_discovery_social_enabled ON discovery_settings(social_enabled);

INSERT INTO profiles (user_id)
SELECT id
FROM users
WHERE deleted_at IS NULL
ON CONFLICT (user_id) DO NOTHING;

INSERT INTO discovery_settings (user_id)
SELECT id
FROM users
WHERE deleted_at IS NULL
ON CONFLICT (user_id) DO NOTHING;

