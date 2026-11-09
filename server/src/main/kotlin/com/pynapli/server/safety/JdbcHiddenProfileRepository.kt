package com.pynapli.server.safety

import com.pynapli.server.db.withConnection
import java.sql.ResultSet
import java.util.UUID
import javax.sql.DataSource

class JdbcHiddenProfileRepository(
    private val dataSource: DataSource,
) : HiddenProfileRepository {
    override suspend fun hideProfile(
        hiderUserId: UUID,
        hiddenUserId: UUID,
        hideIdFactory: () -> UUID,
    ): HiddenProfileRecord =
        dataSource.withConnection { connection ->
            connection
                .prepareStatement(
                    """
                    INSERT INTO user_hidden_profiles (id, hider_user_id, hidden_user_id)
                    VALUES (?, ?, ?)
                    ON CONFLICT (hider_user_id, hidden_user_id) DO UPDATE
                    SET hider_user_id = EXCLUDED.hider_user_id
                    RETURNING id, hider_user_id, hidden_user_id, created_at
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, hideIdFactory())
                    statement.setObject(2, hiderUserId)
                    statement.setObject(3, hiddenUserId)
                    statement.executeQuery().use { rs ->
                        rs.next()
                        rs.toHiddenProfileRecord()
                    }
                }
        }

    override suspend fun unhideProfile(
        hiderUserId: UUID,
        hiddenUserId: UUID,
    ): Boolean =
        dataSource.withConnection { connection ->
            connection
                .prepareStatement(
                    """
                    DELETE FROM user_hidden_profiles
                    WHERE hider_user_id = ?
                      AND hidden_user_id = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, hiderUserId)
                    statement.setObject(2, hiddenUserId)
                    statement.executeUpdate() > 0
                }
        }

    override suspend fun listHiddenProfiles(hiderUserId: UUID): List<HiddenProfileSummaryRecord> =
        dataSource.withConnection { connection ->
            connection
                .prepareStatement(
                    """
                    SELECT hp.hidden_user_id,
                           p.display_name,
                           hp.created_at
                    FROM user_hidden_profiles hp
                    LEFT JOIN profiles p ON p.user_id = hp.hidden_user_id
                    WHERE hp.hider_user_id = ?
                    ORDER BY hp.created_at DESC, hp.hidden_user_id ASC
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, hiderUserId)
                    statement.executeQuery().use { rs ->
                        buildList {
                            while (rs.next()) {
                                add(
                                    HiddenProfileSummaryRecord(
                                        hiddenUserId = rs.getObject("hidden_user_id", UUID::class.java),
                                        hiddenDisplayName = rs.getString("display_name"),
                                        createdAt = rs.getTimestamp("created_at").toInstant(),
                                    ),
                                )
                            }
                        }
                    }
                }
        }

    override suspend fun listHiddenProfileIds(hiderUserId: UUID): Set<UUID> =
        dataSource.withConnection { connection ->
            connection
                .prepareStatement(
                    """
                    SELECT hidden_user_id
                    FROM user_hidden_profiles
                    WHERE hider_user_id = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, hiderUserId)
                    statement.executeQuery().use { rs ->
                        buildSet {
                            while (rs.next()) {
                                add(rs.getObject("hidden_user_id", UUID::class.java))
                            }
                        }
                    }
                }
        }

    private fun ResultSet.toHiddenProfileRecord(): HiddenProfileRecord =
        HiddenProfileRecord(
            id = getObject("id", UUID::class.java),
            hiderUserId = getObject("hider_user_id", UUID::class.java),
            hiddenUserId = getObject("hidden_user_id", UUID::class.java),
            createdAt = getTimestamp("created_at").toInstant(),
        )
}
