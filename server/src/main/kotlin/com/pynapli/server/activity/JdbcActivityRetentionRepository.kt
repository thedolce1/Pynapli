package com.pynapli.server.activity

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Timestamp
import java.time.Instant
import javax.sql.DataSource

class JdbcActivityRetentionRepository(
    private val dataSource: DataSource,
) : ActivityRetentionRepository {
    override suspend fun hideExpiredProductVisibleEvents(now: Instant): ActivityRetentionSummary =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.prepareStatement(HIDE_EXPIRED_EVENTS_QUERY).use { statement ->
                    statement.setTimestamp(1, Timestamp.from(now))
                    statement.setTimestamp(2, Timestamp.from(now))
                    statement.setTimestamp(3, Timestamp.from(now))
                    statement.setTimestamp(4, Timestamp.from(now))
                    statement.setTimestamp(5, Timestamp.from(now))
                    statement.setTimestamp(6, Timestamp.from(now))
                    statement.executeQuery().use { rs ->
                        val hiddenByType = mutableMapOf<String, Int>()
                        var hiddenTotal = 0
                        while (rs.next()) {
                            hiddenTotal += 1
                            val type = rs.getString("event_type")
                            hiddenByType[type] = hiddenByType.getOrDefault(type, 0) + 1
                        }
                        ActivityRetentionSummary(
                            hiddenTotal = hiddenTotal,
                            hiddenByType = hiddenByType.toSortedMap(),
                        )
                    }
                }
            }
        }

    companion object {
        private val HIDE_EXPIRED_EVENTS_QUERY =
            """
            UPDATE activity_events
            SET visibility_status = 'hidden',
                deleted_at = COALESCE(deleted_at, ?),
                updated_at = ?
            WHERE visibility_status = 'visible'
              AND deleted_at IS NULL
              AND (
                  (event_type = 'profile_view' AND occurred_at < (?::timestamptz - interval '30 days'))
                  OR (event_type = 'flirt' AND occurred_at < (?::timestamptz - interval '30 days'))
                  OR (event_type IN ('swipe_like', 'pineapple_like', 'intro_note') AND occurred_at < (?::timestamptz - interval '90 days'))
                  OR (event_type IN ('album_request', 'partner_chat_invite') AND occurred_at < (?::timestamptz - interval '90 days'))
              )
            RETURNING event_type
            """.trimIndent()
    }
}
