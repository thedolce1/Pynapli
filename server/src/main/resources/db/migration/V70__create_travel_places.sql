-- Travel place directory backing the travel-mode type-ahead search
-- (SWIPE_IMPLEMENTATION_SPEC travel follow-up, founder UX decision 2026-06-09:
-- free-text city / ZIP / postal entry instead of a preset picker).
--
-- Rows are bulk-loaded at startup by TravelPlaceDatasetLoader from the packaged
-- GeoNames-derived resource (travel/travel_places.tsv.gz); the dataset table
-- records the loaded version so unchanged restarts skip the reload.
CREATE TABLE travel_places (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    kind TEXT NOT NULL CHECK (kind IN ('city', 'postal')),
    country_code TEXT NOT NULL,
    postal_code TEXT NULL,
    name TEXT NOT NULL,
    ascii_name TEXT NOT NULL,
    region TEXT NULL,
    latitude DOUBLE PRECISION NOT NULL,
    longitude DOUBLE PRECISION NOT NULL,
    population BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_travel_places_city_prefix
    ON travel_places ((lower(ascii_name)) text_pattern_ops)
    WHERE kind = 'city';

CREATE INDEX idx_travel_places_postal_prefix
    ON travel_places (postal_code text_pattern_ops)
    WHERE kind = 'postal';

CREATE TABLE travel_places_dataset (
    singleton BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (singleton),
    version TEXT NOT NULL,
    loaded_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
