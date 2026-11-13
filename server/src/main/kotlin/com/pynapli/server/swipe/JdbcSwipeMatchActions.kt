package com.pynapli.server.swipe

import com.pynapli.server.db.withConnection
import java.util.UUID
import javax.sql.DataSource

internal class JdbcSwipeMatchActions(
    private val dataSource: DataSource,
) {
    suspend fun listMatches(
        viewerUserId: UUID,
        limit: Int,
        after: MatchPageCursor? = null,
    ): List<MatchSummaryRecord> =
        dataSource.withConnection { connection ->
            connection.listMatchSummaries(viewerUserId, limit, after)
        }
}
