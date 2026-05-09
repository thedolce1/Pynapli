-- F4: Lifestyle taxonomy backing.
-- Stored as TEXT[] so a profile can carry multiple tags. Filter logic uses array
-- overlap (`&&`) which a GIN index can serve. NULL means "user opted out" — the
-- viewer-side filter excludes them whenever a lifestyle filter is active, matching
-- the PROFILE_FILTER_SYNC "has X" hard-existence semantics for tagged-content filters.
ALTER TABLE profiles
    ADD COLUMN IF NOT EXISTS lifestyle_tags TEXT[];

CREATE INDEX IF NOT EXISTS idx_profiles_lifestyle_tags
    ON profiles USING GIN (lifestyle_tags)
    WHERE lifestyle_tags IS NOT NULL;
