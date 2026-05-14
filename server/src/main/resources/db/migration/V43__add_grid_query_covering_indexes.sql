CREATE INDEX IF NOT EXISTS idx_users_active_discovery_lookup
ON users(id)
WHERE status = 'active' AND deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_profiles_grid_card_lookup
ON profiles(user_id)
INCLUDE (
    display_name,
    display_age,
    relationship_context,
    discovery_style,
    verified_at,
    face_photo_verified_at,
    age_verified_at,
    tonight_available_until,
    lifestyle_tags,
    logistics_tags,
    intents
)
WHERE visibility_status = 'visible';

CREATE INDEX IF NOT EXISTS idx_discovery_settings_grid_card_lookup
ON discovery_settings(user_id)
INCLUDE (show_distance)
WHERE grid_visible = true AND incognito = false;
