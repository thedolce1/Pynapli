-- Travel-mode 24h Like tail (SWIPE_IMPLEMENTATION_SPEC L347, L353).
-- Records which travel_locations row was active when a swipe action was taken.
-- The match-creation query LEFT JOINs against this to enforce: a travel-mode
-- Like only counts for matching while the travel session is active OR within
-- 24h after it ends. Non-travel Likes (column NULL) are always valid.
ALTER TABLE swipe_actions
ADD COLUMN IF NOT EXISTS travel_location_id UUID
    REFERENCES travel_locations(id) ON DELETE SET NULL;

-- The match query joins on travel_location_id so an index here keeps the
-- match-eligibility check off the seq-scan path.
CREATE INDEX IF NOT EXISTS idx_swipe_actions_travel_location_id
    ON swipe_actions(travel_location_id)
    WHERE travel_location_id IS NOT NULL;
