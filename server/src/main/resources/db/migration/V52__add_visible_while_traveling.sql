-- "Visible while traveling" privacy toggle (PROFILE_SETTINGS_SPEC L221-245).
-- Default true preserves the existing semantic for all users: setting travel
-- mode keeps them discoverable in the destination, just like before. Users
-- who opt out (set false) become invisible to ALL discovery surfaces whenever
-- their travel session is active — Grid, Swipe, and Top Picks alike.
ALTER TABLE discovery_settings
ADD COLUMN IF NOT EXISTS visible_while_traveling BOOLEAN NOT NULL DEFAULT true;
