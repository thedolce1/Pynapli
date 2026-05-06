CREATE TABLE IF NOT EXISTS user_profile_links (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    linked_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    status TEXT NOT NULL DEFAULT 'active',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    accepted_at TIMESTAMPTZ,
    unlinked_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT user_profile_links_not_self_check CHECK (user_id <> linked_user_id),
    CONSTRAINT user_profile_links_status_check CHECK (status IN ('pending', 'active', 'declined', 'unlinked'))
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_user_profile_links_pair
ON user_profile_links(user_id, linked_user_id);

CREATE UNIQUE INDEX IF NOT EXISTS idx_user_profile_links_active_user
ON user_profile_links(user_id)
WHERE status = 'active' AND unlinked_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_user_profile_links_active_linked_user
ON user_profile_links(linked_user_id)
WHERE status = 'active' AND unlinked_at IS NULL;
