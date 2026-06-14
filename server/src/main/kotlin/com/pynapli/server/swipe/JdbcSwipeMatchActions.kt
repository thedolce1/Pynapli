package com.pynapli.server.swipe

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.sql.DataSource

internal class JdbcSwipeMatchActions(
    private val dataSource: DataSource,
) {
    suspend fun listMatches(
        viewerUserId: UUID,
        limit: Int,
    ): List<MatchSummaryRecord> =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.listMatchSummaries(viewerUserId, limit)
            }
        }
}
