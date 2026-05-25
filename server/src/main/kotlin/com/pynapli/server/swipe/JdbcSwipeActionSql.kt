package com.pynapli.server.swipe

import java.sql.Connection
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

internal fun Connection.recordSwipeActionTransaction(
    actionId: UUID,
    actorUserId: UUID,
    targetUserId: UUID,
    action: String,
    noteText: String?,
    consumedInventoryId: UUID?,
    travelLocationId: UUID?,
    matchIdFactory: () -> UUID,
    afterInsert: Connection.() -> Unit = {}
): SwipeActionResult {
    val previousAutoCommit = autoCommit
    autoCommit = false
    try {
        if (!insertSwipeAction(actionId, actorUserId, targetUserId, action, noteText, consumedInventoryId, travelLocationId)) {
            val existing = findSwipeActionById(actionId, actorUserId)
            rollback()
            return SwipeActionResult(
                recorded = existing != null,
                match = existing?.match,
                newlyRecorded = false
            )
        }

        val match = createMatchForMutualPositiveSwipe(actorUserId, targetUserId, action, matchIdFactory)
        afterInsert()

        commit()
        return SwipeActionResult(recorded = true, match = match)
    } catch (error: Throwable) {
        rollback()
        throw error
    } finally {
        autoCommit = previousAutoCommit
    }
}

internal fun Connection.findSwipeActionById(actionId: UUID, actorUserId: UUID): SwipeActionRecord? =
    prepareStatement(
        """
        SELECT sa.id AS action_id,
               sa.actor_user_id,
               sa.target_user_id,
               sa.action,
               sa.note_text,
               sa.created_at AS action_created_at,
               sa.rewound_at,
               m.id AS match_id,
               m.user_a,
               m.user_b,
               m.created_at AS match_created_at
        FROM swipe_actions sa
        LEFT JOIN matches m
          ON (
              (m.user_a = sa.actor_user_id AND m.user_b = sa.target_user_id)
              OR (m.user_a = sa.target_user_id AND m.user_b = sa.actor_user_id)
          )
        WHERE sa.id = ?
          AND sa.actor_user_id = ?
        """.trimIndent()
    ).use { statement ->
        statement.setObject(1, actionId)
        statement.setObject(2, actorUserId)
        statement.executeQuery().use { rs ->
            if (!rs.next()) {
                null
            } else {
                val matchId = rs.getObject("match_id") as? UUID
                SwipeActionRecord(
                    actionId = rs.getObject("action_id", UUID::class.java),
                    actorUserId = rs.getObject("actor_user_id", UUID::class.java),
                    targetUserId = rs.getObject("target_user_id", UUID::class.java),
                    action = rs.getString("action"),
                    noteText = rs.getString("note_text"),
                    createdAt = rs.getTimestamp("action_created_at").toInstant(),
                    rewoundAt = rs.getTimestamp("rewound_at")?.toInstant(),
                    match = matchId?.let {
                        MatchRecord(
                            matchId = it,
                            userA = rs.getObject("user_a", UUID::class.java),
                            userB = rs.getObject("user_b", UUID::class.java),
                            createdAt = rs.getTimestamp("match_created_at").toInstant()
                        )
                    }
                )
            }
        }
    }

internal fun Connection.rewindSwipeActionTransaction(
    actionId: UUID,
    actorUserId: UUID,
    targetUserId: UUID,
    session: SwipeDeckSessionRecord,
    card: SwipeDeckSessionCardRecord,
    rewoundAt: Instant
): Boolean {
    val previousAutoCommit = autoCommit
    autoCommit = false
    try {
        val rewoundAction = markSwipeActionRewound(actionId, actorUserId, targetUserId, rewoundAt)
            ?: run {
                rollback()
                return false
            }

        refundPremiumInventory(rewoundAction.consumedInventoryId, rewoundAt)
        insertDeckSession(session)
        insertDeckCards(listOf(card))
        commit()
        return true
    } catch (error: Throwable) {
        rollback()
        throw error
    } finally {
        autoCommit = previousAutoCommit
    }
}

private fun Connection.insertSwipeAction(
    actionId: UUID,
    actorUserId: UUID,
    targetUserId: UUID,
    action: String,
    noteText: String?,
    consumedInventoryId: UUID?,
    travelLocationId: UUID?
): Boolean = prepareStatement(
    """
    INSERT INTO swipe_actions (
        id, actor_user_id, target_user_id, action,
        note_text, consumed_inventory_id, travel_location_id
    )
    VALUES (?, ?, ?, ?, ?, ?, ?)
    ON CONFLICT DO NOTHING
    """.trimIndent()
).use { statement ->
    statement.setObject(1, actionId)
    statement.setObject(2, actorUserId)
    statement.setObject(3, targetUserId)
    statement.setString(4, action)
    statement.setString(5, noteText)
    statement.setObject(6, consumedInventoryId)
    statement.setObject(7, travelLocationId)
    statement.executeUpdate() > 0
    }

private fun Connection.markSwipeActionRewound(
    actionId: UUID,
    actorUserId: UUID,
    targetUserId: UUID,
    rewoundAt: Instant
): RewoundSwipeAction? = prepareStatement(
    """
    UPDATE swipe_actions sa
    SET rewound_at = ?
    WHERE sa.id = ?
      AND sa.actor_user_id = ?
      AND sa.target_user_id = ?
      AND sa.rewound_at IS NULL
      AND sa.action IN ('like', 'pass', 'pineapple_like')
      AND NOT EXISTS (
          SELECT 1
          FROM matches m
          WHERE (m.user_a = sa.actor_user_id AND m.user_b = sa.target_user_id)
             OR (m.user_a = sa.target_user_id AND m.user_b = sa.actor_user_id)
      )
      AND NOT EXISTS (
          SELECT 1
          FROM safety_reports sr
          WHERE sr.reporter_user_id = sa.actor_user_id
            AND sr.reported_user_id = sa.target_user_id
      )
    RETURNING sa.consumed_inventory_id
    """.trimIndent()
).use { statement ->
    statement.setTimestamp(1, Timestamp.from(rewoundAt))
    statement.setObject(2, actionId)
    statement.setObject(3, actorUserId)
    statement.setObject(4, targetUserId)
    statement.executeQuery().use { rs ->
        if (rs.next()) {
            RewoundSwipeAction(
                consumedInventoryId = rs.getObject("consumed_inventory_id", UUID::class.java)
            )
        } else {
            null
        }
    }
}

private fun Connection.refundPremiumInventory(inventoryId: UUID?, rewoundAt: Instant) {
    if (inventoryId == null) return
    prepareStatement(
        """
        UPDATE premium_inventory
        SET quantity = quantity + 1,
            updated_at = ?
        WHERE id = ?
        """.trimIndent()
    ).use { statement ->
        statement.setTimestamp(1, Timestamp.from(rewoundAt))
        statement.setObject(2, inventoryId)
        statement.executeUpdate()
    }
}

private data class RewoundSwipeAction(
    val consumedInventoryId: UUID?
)

private fun Connection.createMatchForMutualPositiveSwipe(
    actorUserId: UUID,
    targetUserId: UUID,
    action: String,
    matchIdFactory: () -> UUID
): MatchRecord? {
    if (action !in POSITIVE_SWIPE_ACTIONS) return null
    return if (targetHasActivePositiveSwipe(targetUserId, actorUserId)) {
        upsertMatch(actorUserId, targetUserId, matchIdFactory)
    } else {
        null
    }
}

private fun Connection.targetHasActivePositiveSwipe(actorUserId: UUID, targetUserId: UUID): Boolean =
    prepareStatement(
        """
        -- 24h Like tail (SWIPE_IMPLEMENTATION_SPEC L353): a Like
        -- sent in travel mode only counts for matching while the
        -- session is active OR within 24h after it ended. Likes
        -- without a travel_location_id are always valid.
        SELECT EXISTS (
            SELECT 1 FROM swipe_actions sa
            LEFT JOIN travel_locations tl
              ON tl.id = sa.travel_location_id
            WHERE sa.actor_user_id = ?
              AND sa.target_user_id = ?
              AND sa.action IN ('like', 'pineapple_like')
              AND sa.rewound_at IS NULL
              AND (
                  sa.travel_location_id IS NULL
                  OR tl.active_until IS NULL
                  OR tl.active_until > now() - INTERVAL '24 hours'
              )
        )
        """.trimIndent()
    ).use { statement ->
        statement.setObject(1, actorUserId)
        statement.setObject(2, targetUserId)
        statement.executeQuery().use { rs ->
            rs.next()
            rs.getBoolean(1)
        }
    }

private val POSITIVE_SWIPE_ACTIONS = setOf("like", "pineapple_like")
