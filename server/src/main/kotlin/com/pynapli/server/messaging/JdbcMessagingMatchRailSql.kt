package com.pynapli.server.messaging

import java.sql.Connection
import java.util.UUID

/*
 * The Messages new-matches rail (MESSAGING_SPEC "New-matches rail"). The server decides which inbox
 * conversations are new matches for the viewer (ConversationDto.newMatch); the client only renders it.
 *
 * A direct conversation is a new match for the viewer when the pair has a `matches` row, nothing the
 * viewer can see has been posted since the match, and either
 *   - the conversation has no messages the viewer can see at all (a fresh match: it stays in the rail
 *     until the first message, opened or not, as before), or
 *   - the viewer has not acknowledged this match (`match_inbox_acknowledgements`, V115): the two had
 *     talked before the match, so it stays in the rail until the viewer opens the conversation.
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
                  FROM messages msg
                  WHERE msg.conversation_id = dc.conversation_id
                    AND msg.created_at >= mt.created_at
                    AND $VIEWER_VISIBLE_MESSAGE
              )
              AND (
                  NOT EXISTS (
                      SELECT 1
                      FROM messages msg
                      WHERE msg.conversation_id = dc.conversation_id
                        AND $VIEWER_VISIBLE_MESSAGE
                  )
                  OR NOT EXISTS (
                      SELECT 1
                      FROM match_inbox_acknowledgements ack
                      WHERE ack.match_id = mt.id
                        AND ack.user_id = ?
                  )
              )
        )
        """.trimIndent(),
    ).use { statement ->
        statement.setObject(1, conversationId)
        statement.setObject(2, userId)
        statement.setObject(3, userId)
        statement.setObject(4, userId)
        statement.setObject(5, userId)
        statement.executeQuery().use { rs ->
            rs.next()
            rs.getBoolean(1)
        }
    }

/**
 * The viewer opened the conversation (it was marked read): acknowledge the pair's match for this
 * viewer only, so a match between people who had already talked leaves their rail and never returns.
 * Idempotent; a no-op for an unmatched pair, a group thread or a non-pair participant.
 */
internal fun Connection.acknowledgeMatchInInbox(
    userId: UUID,
    conversationId: UUID,
) {
    prepareStatement(
        """
        INSERT INTO match_inbox_acknowledgements (match_id, user_id)
        SELECT mt.id, ?
        FROM direct_conversations dc
        $DIRECT_PAIR_MATCH_JOIN
        WHERE dc.conversation_id = ?
          AND ?::uuid IN (dc.user_a_id, dc.user_b_id)
        ON CONFLICT DO NOTHING
        """.trimIndent(),
    ).use { statement ->
        statement.setObject(1, userId)
        statement.setObject(2, conversationId)
        statement.setObject(3, userId)
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
