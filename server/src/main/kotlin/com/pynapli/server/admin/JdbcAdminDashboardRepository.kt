package com.pynapli.server.admin

import com.pynapli.server.db.withConnection
import java.sql.Connection
import javax.sql.DataSource

internal class JdbcAdminDashboardRepository(
    private val dataSource: DataSource,
) : AdminDashboardRepository {
    override suspend fun counts(): AdminDashboardCounts =
        dataSource.withConnection { connection ->
            val reports = connection.groupedCounts("SELECT status, count(*) FROM safety_reports GROUP BY status")
            val appeals = connection.groupedCounts("SELECT status, count(*) FROM moderation_appeals GROUP BY status")
            val risk = connection.groupedCounts("SELECT tier, count(*) FROM account_risk_states GROUP BY tier")
            AdminDashboardCounts(
                reportsOpen = reports["open"] ?: 0,
                reportsUnderReview = reports["under_review"] ?: 0,
                appealsOpen = appeals["open"] ?: 0,
                appealsUnderReview = appeals["under_review"] ?: 0,
                appealsEscalated = appeals["escalated"] ?: 0,
                heldMessagesOpen = connection.heldMessageCount(),
                riskWatch = risk["watch"] ?: 0,
                riskFriction = risk["friction"] ?: 0,
                riskQuarantine = risk["quarantine"] ?: 0,
                riskTerminate = risk["terminate"] ?: 0,
            )
        }

    // The SQL is a compile-time constant (never user input); it groups one enum column into counts.
    private fun Connection.groupedCounts(sql: String): Map<String, Int> =
        prepareStatement(sql).use { statement ->
            statement.executeQuery().use { rs ->
                buildMap {
                    while (rs.next()) put(rs.getString(1), rs.getInt(2))
                }
            }
        }

    // Held messages still awaiting review: an open queue row whose message is still held and live.
    private fun Connection.heldMessageCount(): Int =
        prepareStatement(
            """
            SELECT count(*)
            FROM message_review_queue q
            JOIN messages m
              ON m.id = q.message_id
             AND m.moderation_status = 'held'
             AND m.deleted_at IS NULL
            WHERE q.status = 'open'
            """.trimIndent(),
        ).use { statement ->
            statement.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }
}
