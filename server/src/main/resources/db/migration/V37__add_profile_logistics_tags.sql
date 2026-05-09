-- F5: Logistics tag backing (hosts / travels / hotel-friendly / public meet first / club-event only).
-- Same shape as lifestyle_tags (V36): TEXT[] with GIN partial index, overlap predicate.
ALTER TABLE profiles
    ADD COLUMN IF NOT EXISTS logistics_tags TEXT[];

CREATE INDEX IF NOT EXISTS idx_profiles_logistics_tags
    ON profiles USING GIN (logistics_tags)
    WHERE logistics_tags IS NOT NULL;
