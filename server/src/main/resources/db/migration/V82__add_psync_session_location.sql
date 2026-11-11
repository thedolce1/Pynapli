-- A Psync deck is dealt around the initiator's location, which is their Travel mode destination
-- when they have one active. The partner was never shown that, so they could agree to a session
-- without knowing it was in another city. The session now records the travel destination it was
-- set up with (NULL = around the initiator's home) and a display label, both partners see it, and
-- the deck is only dealt while it still matches. Delivered likes carry the travel row so the
-- 24-hour travel like tail applies to them too.
ALTER TABLE psync_sessions
    ADD COLUMN IF NOT EXISTS travel_location_id UUID REFERENCES travel_locations(id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS location_label TEXT;
