-- Grid Favorites (GRID_IMPLEMENTATION_SPEC "Favorites"): a private star on another person.
--
-- Private to the owner: adding or removing a favorite writes no activity, notification or push, and
-- the favorited person can never read this table through any route. A favorite of someone who later
-- blocks, hides, goes incognito, is banned or deletes their account stays stored but drops out of the
-- Favorites list (the list applies the same visibility rules as the Grid on every read). Account
-- deletion (a soft delete) removes the rows in both directions (JdbcAccountDeletionActions).
CREATE TABLE IF NOT EXISTS favorite_profiles (
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    favorite_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, favorite_user_id),
    CONSTRAINT favorite_profiles_not_self CHECK (user_id <> favorite_user_id)
);

CREATE INDEX IF NOT EXISTS idx_favorite_profiles_user_created
ON favorite_profiles(user_id, created_at DESC);
