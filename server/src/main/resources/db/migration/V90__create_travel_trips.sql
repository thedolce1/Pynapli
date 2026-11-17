-- Planned trips: Travel mode scheduled ahead ("Visiting Las Vegas 14-17 Nov"). The trip switches
-- the planner's Travel mode on at local midnight on starts_on and off after ends_on, in the
-- timezone they planned it from. travel_location_id points at the travel_locations row the trip
-- activated, so ending the trip only clears Travel mode if the user hasn't switched since.
CREATE TABLE IF NOT EXISTS travel_trips (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    city TEXT NOT NULL,
    region TEXT,
    country TEXT,
    latitude DOUBLE PRECISION NOT NULL,
    longitude DOUBLE PRECISION NOT NULL,
    starts_on DATE NOT NULL,
    ends_on DATE NOT NULL,
    timezone TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'planned',
    travel_location_id UUID REFERENCES travel_locations(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT travel_trips_status_check CHECK (status IN ('planned', 'active', 'done', 'cancelled')),
    CONSTRAINT travel_trips_dates_check CHECK (ends_on >= starts_on AND ends_on - starts_on <= 30),
    CONSTRAINT travel_trips_city_check CHECK (length(trim(city)) BETWEEN 1 AND 120)
);

CREATE INDEX IF NOT EXISTS idx_travel_trips_user_upcoming
    ON travel_trips(user_id, starts_on)
    WHERE status IN ('planned', 'active');

CREATE INDEX IF NOT EXISTS idx_travel_trips_due
    ON travel_trips(status, starts_on)
    WHERE status IN ('planned', 'active');
