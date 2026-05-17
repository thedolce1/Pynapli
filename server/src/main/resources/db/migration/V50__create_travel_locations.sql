-- Passport/travel mode (DATA_MODEL_SPEC L286-301, SWIPE_IMPLEMENTATION_SPEC L339-354).
-- One active row per user at a time (enforced by partial unique index); history
-- rows are retained with status='cleared' or 'expired' for audit + the 24h Like
-- tail rule (SWIPE_IMPLEMENTATION_SPEC L353) which a later slice will read.
CREATE TABLE IF NOT EXISTS travel_locations (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    city TEXT NOT NULL,
    region TEXT,
    country TEXT,
    geom geography(Point, 4326) NOT NULL,
    active_from TIMESTAMPTZ NOT NULL DEFAULT now(),
    active_until TIMESTAMPTZ,
    status TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT travel_locations_status_check CHECK (status IN ('active', 'cleared', 'expired')),
    CONSTRAINT travel_locations_city_check CHECK (length(trim(city)) BETWEEN 1 AND 120),
    CONSTRAINT travel_locations_window_check CHECK (active_until IS NULL OR active_until > active_from)
);

-- One active travel row per user. Existing active row must be cleared
-- (status set + active_until populated) before a new row can be inserted.
CREATE UNIQUE INDEX IF NOT EXISTS uniq_travel_locations_user_active
    ON travel_locations(user_id)
    WHERE status = 'active' AND active_until IS NULL;

-- History/audit lookups + the 24h Like tail query (later slice) will join on
-- user + recent active_until.
CREATE INDEX IF NOT EXISTS idx_travel_locations_user_active_from
    ON travel_locations(user_id, active_from DESC);

-- Spatial index for the rare query that ranges over travel rows directly. The
-- hot discovery path joins by user_id then uses the resulting point against
-- the existing GiST index on user_locations.geom.
CREATE INDEX IF NOT EXISTS idx_travel_locations_geom
    ON travel_locations USING GIST (geom);
