-- Last seen (GRID_IMPLEMENTATION_SPEC "Presence States"): the most recent foreground activity per user,
-- written at most about once a minute from the presence heartbeat. Only ever read server-side to pick a
-- coarse bucket ("Active today"); the timestamp itself is never returned to another user.
CREATE TABLE IF NOT EXISTS user_last_active (
    user_id UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    last_active_at TIMESTAMPTZ NOT NULL
);
