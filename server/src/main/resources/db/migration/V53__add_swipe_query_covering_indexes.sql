CREATE INDEX IF NOT EXISTS idx_discovery_settings_swipe_card_lookup
ON discovery_settings(user_id)
INCLUDE (show_distance, visible_while_traveling)
WHERE swipe_visible = true AND incognito = false;
