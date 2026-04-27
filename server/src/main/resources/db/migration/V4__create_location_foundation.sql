CREATE TABLE IF NOT EXISTS user_locations (
    user_id UUID PRIMARY KEY REFERENCES users(id),
    geom GEOGRAPHY(Point, 4326) NOT NULL,
    geohash TEXT,
    city TEXT,
    region TEXT,
    country TEXT,
    location_source TEXT NOT NULL,
    accuracy_meters INTEGER,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT user_locations_source_check CHECK (location_source IN ('gps', 'network', 'travel', 'manual_city')),
    CONSTRAINT user_locations_accuracy_check CHECK (accuracy_meters IS NULL OR (accuracy_meters >= 0 AND accuracy_meters <= 100000))
);

CREATE INDEX IF NOT EXISTS idx_user_locations_geom ON user_locations USING GIST (geom);
CREATE INDEX IF NOT EXISTS idx_user_locations_geohash ON user_locations(geohash);
CREATE INDEX IF NOT EXISTS idx_user_locations_updated_at ON user_locations(updated_at);

