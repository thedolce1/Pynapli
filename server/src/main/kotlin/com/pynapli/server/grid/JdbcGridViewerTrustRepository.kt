package com.pynapli.server.grid

import com.pynapli.server.db.withConnection
import java.util.UUID
import javax.sql.DataSource

class JdbcGridViewerTrustRepository(
    private val dataSource: DataSource,
) : GridViewerTrustRepository {
    override suspend fun isAgeVerified(userId: UUID): Boolean =
        dataSource.withConnection { connection ->
            connection
                .prepareStatement(
                    """
                    SELECT EXISTS (
                        SELECT 1
                        FROM profiles
                        WHERE user_id = ?
                          AND age_verified_at IS NOT NULL
                    )
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, userId)
                    statement.executeQuery().use { rs ->
                        rs.next()
                        rs.getBoolean(1)
                    }
                }
        }
}
