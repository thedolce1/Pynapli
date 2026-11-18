-- The full-screen "It's a match" moment is shown exactly once per person per match, across devices
-- (SWIPE_IMPLEMENTATION_SPEC "Match moment"). A row here means this person has seen it. The person
-- who made the match sees it from their swipe response; the other person sees it the next time they
-- are in the app (live via the match activity delta, otherwise on foreground). Both mark it seen.
CREATE TABLE match_celebrations (
    match_id UUID NOT NULL REFERENCES matches(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    celebrated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (match_id, user_id)
);

-- Matches made before this existed were already announced by the old in-Swipe overlay or the push:
-- mark them seen for both people, so nobody gets a flood of old celebrations on the next open.
INSERT INTO match_celebrations (match_id, user_id)
SELECT id, user_a FROM matches
UNION ALL
SELECT id, user_b FROM matches
ON CONFLICT DO NOTHING;
