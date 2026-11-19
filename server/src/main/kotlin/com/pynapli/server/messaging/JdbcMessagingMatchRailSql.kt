package com.pynapli.server.messaging

import java.sql.Connection
import java.util.UUID

/*
 * The Messages new-matches rail (MESSAGING_SPEC "New-Matches Rail"). The server decides which inbox
 * conversations are new matches for the viewer (ConversationDto.newMatch); the client only renders it.
 *
 * A direct conversation is a new match for the viewer when the pair has a `matches` row, the viewer
 * has no `rail_cleared` acknowledgement for it (`match_inbox_acknowledgements`, V115), and nothing the
 * viewer can see has been posted since the match. An acknowledgement with `rail_cleared` is written
 *   - for both people when a message becomes visible in the conversation ([consumeMatchInInbox],
 *     in the message's own transaction), so the item never comes back, even after expired messages
 *     are purged;
 *   - for the viewer alone when they open a conversation that already has messages
 *     ([acknowledgeMatchInInbox]): a match with someone they had already talked to.
 * Opening an empty match records `rail_cleared = false`: it stays in the rail until the first message.
 * The "posted since the match" check keeps the per-viewer view of a held message: its sender sees it
 * (their item clears), the other person does not (theirs stays until it is released).
 * An unmatch deletes the match (and its acknowledgements), which ends it; blocks drop the whole
 * conversation in MessagingConversationGuard, which takes the rail item with it.
 */

/** Joins `dc` (direct_conversations) to its pair's match row as `mt`. */
private const val DIRECT_PAIR_MATCH_JOIN =
    """
    JOIN matches mt
      ON mt.user_a = LEAST(dc.user_a_id, dc.user_b_id)
     AND mt.user_b = GREATEST(dc.user_a_id, dc.user_b_id)
    """

/**
 * Inbox listing clause (correlated on `c`): a matched pair's direct conversation is listed for both
 * people even while it has no messages, so the person who did not create it sees the match too.
 */
internal const val MATCHED_DIRECT_CONVERSATION_CLAUSE =
    """
    EXISTS (
        SELECT 1
        FROM direct_conversations dc
        $DIRECT_PAIR_MATCH_JOIN
        WHERE dc.conversation_id = c.id
    )
    """

/** Messages of `msg` the viewer (bound once) can see: same rule as the inbox listing. */
private const val VIEWER_VISIBLE_MESSAGE =
    """
    (
        (msg.deleted_at IS NOT NULL AND msg.moderation_status = 'visible')
        OR (
            msg.deleted_at IS NULL
            AND (
                msg.moderation_status = 'visible'
                OR (msg.moderation_status = 'held' AND msg.sender_user_id = ?)
            )
        )
    )
    """

internal fun Connection.loadNewMatch(
    userId: UUID,
    conversationId: UUID,
): Boolean =
    prepareStatement(
        """
        SELECT EXISTS (
            SELECT 1
            FROM direct_conversations dc
            $DIRECT_PAIR_MATCH_JOIN
            WHERE dc.conversation_id = ?
              AND ?::uuid IN (dc.user_a_id, dc.user_b_id)
              AND NOT EXISTS (
                  SELECT 1
                  FROM match_inbox_acknowledgements ack
                  WHERE ack.match_id = mt.id
                    AND ack.user_id = ?
                    AND ack.rail_cleared
              )
              AND NOT EXISTS (
                  SELECT 1
                  FROM messages msg
                  WHERE msg.conversation_id = dc.conversation_id
                    AND msg.created_at >= mt.created_at
                    AND $VIEWER_VISIBLE_MESSAGE
              )
        )
        """.trimIndent(),
    ).use { statement ->
        statement.setObject(1, conversationId)
        statement.setObject(2, userId)
        statement.setObject(3, userId)
        statement.setObject(4, userId)
        statement.executeQuery().use { rs ->
            rs.next()
            rs.getBoolean(1)
        }
    }

/**
 * The viewer opened the conversation (it was marked read). If it already has messages, the match
 * leaves this viewer's rail for good (`rail_cleared`); if it is still empty, the open is recorded but
 * the match stays until the first message. Idempotent, never un-clears; a no-op for an unmatched
 * pair or a participant outside the pair.
 */
internal fun Connection.acknowledgeMatchInInbox(
    userId: UUID,
    conversationId: UUID,
) {
    prepareStatement(
        """
        INSERT INTO match_inbox_acknowledgements (match_id, user_id, rail_cleared)
        SELECT mt.id,
               ?,
               EXISTS (
                   SELECT 1
                   FROM messages msg
                   WHERE msg.conversation_id = dc.conversation_id
                     AND $VIEWER_VISIBLE_MESSAGE
               )
        FROM direct_conversations dc
        $DIRECT_PAIR_MATCH_JOIN
        WHERE dc.conversation_id = ?
          AND ?::uuid IN (dc.user_a_id, dc.user_b_id)
        ON CONFLICT (match_id, user_id) DO UPDATE
        SET rail_cleared = match_inbox_acknowledgements.rail_cleared OR EXCLUDED.rail_cleared
        """.trimIndent(),
    ).use { statement ->
        statement.setObject(1, userId)
        statement.setObject(2, userId)
        statement.setObject(3, conversationId)
        statement.setObject(4, userId)
        statement.executeUpdate()
    }
}

/**
 * A message became visible in [conversationId] (sent, or released from review): if the pair is
 * matched, the match leaves both people's rails for good. Call it on the connection, and inside the
 * transaction, that made the message visible. Writes nothing once both are cleared.
 */
internal fun Connection.consumeMatchInInbox(conversationId: UUID) {
    prepareStatement(
        """
        INSERT INTO match_inbox_acknowledgements (match_id, user_id, rail_cleared)
        SELECT mt.id, person.user_id, true
        FROM direct_conversations dc
        $DIRECT_PAIR_MATCH_JOIN
        CROSS JOIN LATERAL (VALUES (dc.user_a_id), (dc.user_b_id)) AS person(user_id)
        WHERE dc.conversation_id = ?
          AND NOT EXISTS (
              SELECT 1
              FROM match_inbox_acknowledgements ack
              WHERE ack.match_id = mt.id
                AND ack.user_id = person.user_id
                AND ack.rail_cleared
          )
        ON CONFLICT (match_id, user_id) DO UPDATE
        SET rail_cleared = true
        """.trimIndent(),
    ).use { statement ->
        statement.setObject(1, conversationId)
        statement.executeUpdate()
    }
}

/**
 * A match reusing an older conversation moves it up to the match time, so it is on the first inbox
 * page (where the rail is built) instead of buried under newer threads. Only ever moves it forward, so
 * a redelivered match side effect changes nothing.
 */
internal fun Connection.bringMatchedConversationForward(conversationId: UUID) {
    prepareStatement(
        """
        UPDATE conversations c
        SET updated_at = mt.created_at
        FROM direct_conversations dc
        $DIRECT_PAIR_MATCH_JOIN
        WHERE c.id = ?
          AND dc.conversation_id = c.id
          AND c.updated_at < mt.created_at
        """.trimIndent(),
    ).use { statement ->
        statement.setObject(1, conversationId)
        statement.executeUpdate()
    }
}
