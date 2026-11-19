-- Top Picks was removed (2026-11-18): the route, service and client mode are gone, so the
-- tables V49 created hold nothing anything reads. Cards first: they reference the sessions.
DROP TABLE IF EXISTS top_pick_session_cards;
DROP TABLE IF EXISTS top_pick_sessions;
