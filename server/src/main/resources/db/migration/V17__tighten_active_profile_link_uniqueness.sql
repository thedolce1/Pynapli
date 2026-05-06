CREATE UNIQUE INDEX IF NOT EXISTS idx_user_profile_links_active_linked_user_unique
ON user_profile_links(linked_user_id)
WHERE status = 'active' AND unlinked_at IS NULL;
