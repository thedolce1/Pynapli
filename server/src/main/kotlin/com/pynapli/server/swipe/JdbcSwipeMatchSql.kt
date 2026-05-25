package com.pynapli.server.swipe

import java.sql.Connection
import java.util.UUID

internal fun Connection.upsertMatch(
    actorUserId: UUID,
    targetUserId: UUID,
    matchIdFactory: () -> UUID
): MatchRecord {
    val (userA, userB) = canonicalPair(actorUserId, targetUserId)
    return prepareStatement(
        """
        INSERT INTO matches (id, user_a, user_b)
        VALUES (?, ?, ?)
        ON CONFLICT (user_a, user_b) DO UPDATE
        SET user_a = EXCLUDED.user_a
        RETURNING id, user_a, user_b, created_at
        """.trimIndent()
    ).use { statement ->
        statement.setObject(1, matchIdFactory())
        statement.setObject(2, userA)
        statement.setObject(3, userB)
        statement.executeQuery().use { rs ->
            rs.next()
            MatchRecord(
                matchId = rs.getObject("id", UUID::class.java),
                userA = rs.getObject("user_a", UUID::class.java),
                userB = rs.getObject("user_b", UUID::class.java),
                createdAt = rs.getTimestamp("created_at").toInstant()
            )
        }
    }
}

internal fun canonicalPair(a: UUID, b: UUID): Pair<UUID, UUID> =
    if (a.toString() < b.toString()) a to b else b to a
