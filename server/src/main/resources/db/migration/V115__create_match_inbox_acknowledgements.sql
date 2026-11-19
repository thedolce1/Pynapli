-- The Messages new-matches rail is decided by the server (ConversationDto.newMatch, MESSAGING_SPEC
-- "New-matches rail"). A match whose conversation already had messages before the match (they talked
-- from the Grid first) shows in the rail until this person opens the conversation, which writes a row
-- here (through POST /v1/messages/threads/{id}/read). A row means this person has acknowledged the
-- match in their inbox; each person has their own. An unmatch or account deletion removes the rows
-- with the match.
CREATE TABLE match_inbox_acknowledgements (
    match_id UUID NOT NULL REFERENCES matches(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    acknowledged_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (match_id, user_id)
);

-- Matches made before this existed count as acknowledged for both people, so an old Grid thread does
-- not suddenly jump into the rail. A match whose conversation still has no messages is unaffected:
-- the rail shows an empty match conversation until the first message, acknowledged or not (as before).
INSERT INTO match_inbox_acknowledgements (match_id, user_id)
SELECT id, user_a FROM matches
UNION ALL
SELECT id, user_b FROM matches
ON CONFLICT DO NOTHING;
