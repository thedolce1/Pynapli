-- The Messages new-matches rail is decided by the server (ConversationDto.newMatch, MESSAGING_SPEC
-- "New-Matches Rail"). A row here records, for one person and one match, that the match has been
-- acted on in their inbox:
--   * rail_cleared = true: the item has left this person's rail for good. Written for BOTH people
--     when a message becomes visible in the pair's conversation (sent, or released from review), in
--     the same transaction, and for the opener alone when they open a conversation that already has
--     messages (POST /v1/messages/threads/{id}/read). Durable, so a later purge of expired messages
--     can never bring the item back.
--   * rail_cleared = false: this person opened the match while it had no messages. The rail keeps an
--     empty match until the first message, opened or not.
-- An unmatch or account deletion removes the rows with the match.
CREATE TABLE match_inbox_acknowledgements (
    match_id UUID NOT NULL REFERENCES matches(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    rail_cleared BOOLEAN NOT NULL,
    acknowledged_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (match_id, user_id)
);

-- Matches made before this existed: a match whose conversation has any message is done for both
-- people, so an old Grid thread does not suddenly jump into the rail. A match with no messages gets no
-- row and stays a new match until its first message, as before.
INSERT INTO match_inbox_acknowledgements (match_id, user_id, rail_cleared)
SELECT mt.id, person.user_id, true
FROM matches mt
JOIN direct_conversations dc
  ON LEAST(dc.user_a_id, dc.user_b_id) = mt.user_a
 AND GREATEST(dc.user_a_id, dc.user_b_id) = mt.user_b
CROSS JOIN LATERAL (VALUES (mt.user_a), (mt.user_b)) AS person(user_id)
WHERE EXISTS (
    SELECT 1
    FROM messages msg
    WHERE msg.conversation_id = dc.conversation_id
      AND msg.moderation_status = 'visible'
)
ON CONFLICT DO NOTHING;
