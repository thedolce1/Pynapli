package com.pynapli.server.safety

import com.pynapli.server.db.withConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.ResultSet
import java.util.UUID
import javax.sql.DataSource

class JdbcBlockRepository(
    private val dataSource: DataSource,
) : BlockRepository {
    override suspend fun block(
        blockerUserId: UUID,
        blockedUserId: UUID,
        reason: String?,
        blockIdFactory: () -> UUID,
    ): BlockRecord =
        dataSource.withConnection { connection ->
            connection
                .prepareStatement(
                    """
                    INSERT INTO user_blocks (id, blocker_user_id, blocked_user_id, reason)
                    VALUES (?, ?, ?, ?)
                    ON CONFLICT (blocker_user_id, blocked_user_id) DO UPDATE
                    SET reason = COALESCE(EXCLUDED.reason, user_blocks.reason)
                    RETURNING id, blocker_user_id, blocked_user_id, reason, created_at
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, blockIdFactory())
                    statement.setObject(2, blockerUserId)
                    statement.setObject(3, blockedUserId)
                    statement.setString(4, reason)
                    statement.executeQuery().use { rs ->
                        rs.next()
                        rs.toBlockRecord()
                    }
                }
        }

    override suspend fun unblock(
        blockerUserId: UUID,
        blockedUserId: UUID,
    ): Boolean =
        dataSource.withConnection { connection ->
            connection
                .prepareStatement(
                    """
                    DELETE FROM user_blocks
                    WHERE blocker_user_id = ?
                      AND blocked_user_id = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, blockerUserId)
                    statement.setObject(2, blockedUserId)
                    statement.executeUpdate() > 0
                }
        }

    override suspend fun listBlocks(blockerUserId: UUID): List<BlockSummaryRecord> =
        dataSource.withConnection { connection ->
            connection
                .prepareStatement(
                    """
                    SELECT b.blocked_user_id,
                           p.display_name,
                           b.reason,
                           b.created_at
                    FROM user_blocks b
                    LEFT JOIN profiles p ON p.user_id = b.blocked_user_id
                    WHERE b.blocker_user_id = ?
                    ORDER BY b.created_at DESC, b.blocked_user_id ASC
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, blockerUserId)
                    statement.executeQuery().use { rs ->
                        buildList {
                            while (rs.next()) {
                                add(
                                    BlockSummaryRecord(
                                        blockedUserId = rs.getObject("blocked_user_id", UUID::class.java),
                                        blockedDisplayName = rs.getString("display_name"),
                                        reason = rs.getString("reason"),
                                        createdAt = rs.getTimestamp("created_at").toInstant(),
                                    ),
                                )
                            }
                        }
                    }
                }
        }

    override suspend fun isBlockedEitherWay(
        userIdA: UUID,
        userIdB: UUID,
    ): Boolean =
        withContext(Dispatchers.IO) {
            if (userIdA == userIdB) return@withContext false
            dataSource.connection.use { connection ->
                connection
                    .prepareStatement(
                        """
                        SELECT EXISTS (
                            SELECT 1 FROM user_blocks
                            WHERE (blocker_user_id = ? AND blocked_user_id = ?)
                               OR (blocker_user_id = ? AND blocked_user_id = ?)
                        )
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setObject(1, userIdA)
                        statement.setObject(2, userIdB)
                        statement.setObject(3, userIdB)
                        statement.setObject(4, userIdA)
                        statement.executeQuery().use { rs ->
                            rs.next()
                            rs.getBoolean(1)
                        }
                    }
            }
        }

    override suspend fun listBlockedRelatedUserIds(userId: UUID): Set<UUID> =
        dataSource.withConnection { connection ->
            connection
                .prepareStatement(
                    """
                    SELECT blocked_user_id AS related_id FROM user_blocks WHERE blocker_user_id = ?
                    UNION
                    SELECT blocker_user_id AS related_id FROM user_blocks WHERE blocked_user_id = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, userId)
                    statement.setObject(2, userId)
                    statement.executeQuery().use { rs ->
                        buildSet {
                            while (rs.next()) {
                                add(rs.getObject("related_id", UUID::class.java))
                            }
                        }
                    }
                }
        }

    private fun ResultSet.toBlockRecord(): BlockRecord =
        BlockRecord(
            id = getObject("id", UUID::class.java),
            blockerUserId = getObject("blocker_user_id", UUID::class.java),
            blockedUserId = getObject("blocked_user_id", UUID::class.java),
            reason = getString("reason"),
            createdAt = getTimestamp("created_at").toInstant(),
        )
}
