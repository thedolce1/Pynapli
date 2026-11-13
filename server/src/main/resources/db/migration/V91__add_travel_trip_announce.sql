-- "Visiting soon" is opt-in per trip (founder decision 2026-11-13): locals only see an upcoming
-- visitor when the traveller turned this on for that trip. Off by default.
ALTER TABLE travel_trips
    ADD COLUMN IF NOT EXISTS announce_to_locals BOOLEAN NOT NULL DEFAULT false;
