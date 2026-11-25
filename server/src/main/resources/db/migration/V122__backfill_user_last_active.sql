-- Grid activity tier and Fresh (GRID_IMPLEMENTATION_SPEC "Ranking" demotion tier and "Fresh", plan G3/G4)
-- read only user_last_active (one primary-key probe per candidate) instead of also scanning every
-- user's sessions. From this release every authenticated request writes that row (at most once a
-- minute), so only history needs carrying over: copy each account's newest live-session activity in
-- where it is later than what the row already holds. Idempotent; nothing reads the value but the server.
INSERT INTO user_last_active (user_id, last_active_at)
SELECT us.user_id, max(us.last_seen_at)
FROM user_sessions us
WHERE us.revoked_at IS NULL
GROUP BY us.user_id
ON CONFLICT (user_id) DO UPDATE
SET last_active_at = EXCLUDED.last_active_at
WHERE user_last_active.last_active_at < EXCLUDED.last_active_at;
