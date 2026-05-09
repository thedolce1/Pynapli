-- F6: "Available tonight" acute availability.
-- A single TTL timestamp on the profile. NULL = not opted in.
-- Future (> now()) = currently signalling "playing tonight". Past timestamps are
-- harmless leftovers; the filter predicate checks `> now()` so no scanner is needed.
ALTER TABLE profiles
    ADD COLUMN IF NOT EXISTS tonight_available_until TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_profiles_tonight_available
    ON profiles(tonight_available_until)
    WHERE tonight_available_until IS NOT NULL;
