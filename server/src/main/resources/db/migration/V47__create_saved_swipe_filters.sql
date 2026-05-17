-- D1: Saved swipe filter presets (premium feature, mirrors V39 saved_grid_filters).
-- Payload is the verbatim SwipeDeckRequestDto JSON minus cursor/limit; the server
-- re-validates the payload through `discoveryFiltersFrom` and the
-- `SwipeDealbreakerKey` allow-list on apply so any future taxonomy or dealbreaker
-- change is enforced retroactively.
CREATE TABLE IF NOT EXISTS saved_swipe_filters (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name TEXT NOT NULL,
    payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT saved_swipe_filters_name_check CHECK (length(name) BETWEEN 1 AND 60)
);

CREATE INDEX IF NOT EXISTS idx_saved_swipe_filters_user_created
    ON saved_swipe_filters(user_id, created_at DESC);

CREATE UNIQUE INDEX IF NOT EXISTS uniq_saved_swipe_filters_user_name
    ON saved_swipe_filters(user_id, lower(name));
