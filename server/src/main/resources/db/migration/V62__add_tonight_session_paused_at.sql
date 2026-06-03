-- Playing Tonight partner-offline pause/end (PLAYING_TONIGHT_SPEC Edge Cases:
-- "Partner goes offline: pause linked-together session briefly, then end if not restored.")
--
-- A paused session keeps status='active' but carries a paused_at timestamp. While paused it is
-- hidden from discovery (feed + grid) and resumes (paused_at cleared) if the participant returns,
-- or is ended once it stays offline past the grace window. Modeling pause as a timestamp avoids
-- changing the status CHECK constraint and keeps current()/end semantics intact.
ALTER TABLE tonight_sessions ADD COLUMN IF NOT EXISTS paused_at TIMESTAMPTZ;
