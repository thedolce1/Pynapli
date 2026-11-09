package com.pynapli.server.activity

import com.pynapli.server.db.withConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Timestamp
import java.time.Duration
import java.util.UUID
import javax.sql.DataSource

class JdbcActivityRepository(
    private val dataSource: DataSource,
    private val profileViewCooldown: Duration = Duration.ofMinutes(30),
    private val flirtCooldown: Duration = Duration.ofMinutes(15),
    private val albumRequestCooldown: Duration = Duration.ofHours(24),
) : ActivityRepository {
    override suspend fun recordProfileView(
        viewerUserId: UUID,
        targetUserId: UUID,
    ): Boolean =
        withContext(Dispatchers.IO) {
            if (viewerUserId == targetUserId) return@withContext false

            dataSource.connection.use { connection ->
                connection.prepareStatement(JdbcActivitySql.UPSERT_ELIGIBLE_PROFILE_VIEW_QUERY).use { statement ->
                    statement.setObject(1, UUID.randomUUID())
                    statement.setObject(2, targetUserId)
                    statement.setObject(3, viewerUserId)
                    statement.setObject(4, targetUserId)
                    statement.setObject(5, viewerUserId)
                    statement.setObject(6, targetUserId)
                    statement.setString(7, "${profileViewCooldown.toMinutes()} minutes")
                    statement.setString(8, "${profileViewCooldown.toMinutes()} minutes")
                    statement.setString(9, "${profileViewCooldown.toMinutes()} minutes")
                    statement.executeQuery().use { rs -> rs.next() }
                }
            }
        }

    override suspend fun recordFlirt(
        senderUserId: UUID,
        targetUserId: UUID,
        source: String?,
    ): Boolean =
        withContext(Dispatchers.IO) {
            if (senderUserId == targetUserId) return@withContext false

            dataSource.connection.use { connection ->
                val eligible =
                    connection.prepareStatement(JdbcActivitySql.FLIRT_ELIGIBILITY_QUERY).use { statement ->
                        statement.setObject(1, senderUserId)
                        statement.setObject(2, targetUserId)
                        statement.executeQuery().use { rs ->
                            rs.next()
                            rs.getBoolean(1)
                        }
                    }
                if (!eligible) return@withContext false

                val metadataJson = source?.let { """{"source":"$it"}""" }
                connection.prepareStatement(JdbcActivitySql.UPSERT_FLIRT_QUERY).use { statement ->
                    statement.setObject(1, UUID.randomUUID())
                    statement.setObject(2, targetUserId)
                    statement.setObject(3, senderUserId)
                    statement.setObject(4, targetUserId)
                    statement.setString(5, metadataJson)
                    statement.setString(6, "${flirtCooldown.toMinutes()} minutes")
                    statement.setString(7, "${flirtCooldown.toMinutes()} minutes")
                    statement.setString(8, metadataJson)
                    statement.setString(9, "${flirtCooldown.toMinutes()} minutes")
                    statement.executeUpdate() > 0
                }
            }
        }

    override suspend fun recordAlbumRequest(
        requesterUserId: UUID,
        targetUserId: UUID,
    ): Boolean =
        withContext(Dispatchers.IO) {
            if (requesterUserId == targetUserId) return@withContext false

            dataSource.connection.use { connection ->
                val eligible =
                    connection.prepareStatement(JdbcActivitySql.ALBUM_REQUEST_ELIGIBILITY_QUERY).use { statement ->
                        statement.setObject(1, requesterUserId)
                        statement.setObject(2, targetUserId)
                        statement.executeQuery().use { rs ->
                            rs.next()
                            rs.getBoolean(1)
                        }
                    }
                if (!eligible) return@withContext false

                connection.prepareStatement(JdbcActivitySql.UPSERT_ALBUM_REQUEST_QUERY).use { statement ->
                    statement.setObject(1, UUID.randomUUID())
                    statement.setObject(2, targetUserId)
                    statement.setObject(3, requesterUserId)
                    statement.setObject(4, targetUserId)
                    statement.setString(5, "${albumRequestCooldown.toHours()} hours")
                    statement.setString(6, "${albumRequestCooldown.toHours()} hours")
                    statement.setString(7, "${albumRequestCooldown.toHours()} hours")
                    statement.executeUpdate() > 0
                }
            }
        }

    override suspend fun recordSwipeInterest(
        actorUserId: UUID,
        targetUserId: UUID,
        eventType: String,
    ): Boolean =
        withContext(Dispatchers.IO) {
            if (actorUserId == targetUserId || eventType !in JdbcActivitySql.SWIPE_INTEREST_EVENT_TYPES) return@withContext false

            dataSource.connection.use { connection ->
                connection.prepareStatement(JdbcActivitySql.UPSERT_SWIPE_INTEREST_QUERY).use { statement ->
                    statement.setObject(1, UUID.randomUUID())
                    statement.setObject(2, targetUserId)
                    statement.setObject(3, actorUserId)
                    statement.setString(4, eventType)
                    statement.setObject(5, targetUserId)
                    statement.setObject(6, actorUserId)
                    statement.setObject(7, targetUserId)
                    statement.executeQuery().use { rs -> rs.next() }
                }
            }
        }

    override suspend fun recordMatch(
        recipientUserId: UUID,
        actorUserId: UUID,
        matchId: UUID,
    ): Boolean =
        withContext(Dispatchers.IO) {
            if (recipientUserId == actorUserId) return@withContext false

            dataSource.connection.use { connection ->
                connection.prepareStatement(JdbcActivitySql.UPSERT_MATCH_ACTIVITY_QUERY).use { statement ->
                    statement.setObject(1, UUID.randomUUID())
                    statement.setObject(2, recipientUserId)
                    statement.setObject(3, actorUserId)
                    statement.setObject(4, recipientUserId)
                    statement.setString(5, """{"matchId":"$matchId"}""")
                    statement.setObject(6, actorUserId)
                    statement.setObject(7, recipientUserId)
                    statement.executeQuery().use { rs -> rs.next() }
                }
            }
        }

    override suspend fun recordPartnerChatInvite(
        inviterUserId: UUID,
        inviteeUserId: UUID,
        conversationId: UUID,
    ): Boolean =
        withContext(Dispatchers.IO) {
            if (inviterUserId == inviteeUserId) return@withContext false

            dataSource.connection.use { connection ->
                connection.prepareStatement(JdbcActivitySql.UPSERT_PARTNER_CHAT_INVITE_QUERY).use { statement ->
                    statement.setObject(1, UUID.randomUUID())
                    statement.setObject(2, inviteeUserId)
                    statement.setObject(3, inviterUserId)
                    statement.setObject(4, inviteeUserId)
                    statement.setString(5, """{"conversationId":"$conversationId"}""")
                    statement.setObject(6, inviterUserId)
                    statement.setObject(7, inviteeUserId)
                    statement.executeQuery().use { rs -> rs.next() }
                }
            }
        }

    override suspend fun recordPsyncSessionEvent(
        recipientUserId: UUID,
        actorUserId: UUID,
        eventType: String,
        sessionId: UUID,
    ): Boolean =
        withContext(Dispatchers.IO) {
            if (recipientUserId == actorUserId || eventType !in JdbcActivitySql.PSYNC_SESSION_EVENT_TYPES) {
                return@withContext false
            }

            dataSource.connection.use { connection ->
                connection.prepareStatement(JdbcActivitySql.UPSERT_PSYNC_SESSION_EVENT_QUERY).use { statement ->
                    statement.setObject(1, UUID.randomUUID())
                    statement.setObject(2, recipientUserId)
                    statement.setObject(3, actorUserId)
                    statement.setString(4, eventType)
                    statement.setObject(5, actorUserId)
                    statement.setString(6, """{"sessionId":"$sessionId"}""")
                    statement.setObject(7, actorUserId)
                    statement.setObject(8, recipientUserId)
                    statement.executeQuery().use { rs -> rs.next() }
                }
            }
        }

    override suspend fun listActivity(query: ActivityFeedQuery): List<ActivityEventRecord> =
        dataSource.withConnection { connection ->
            connection.prepareStatement(JdbcActivitySql.ACTIVITY_FEED_QUERY).use { statement ->
                val eventTypeArray = connection.createArrayOf("text", query.eventTypes.toTypedArray())
                statement.setObject(1, query.recipientUserId)
                statement.setObject(2, query.recipientUserId)
                statement.setBoolean(3, query.eventTypes.isEmpty())
                statement.setArray(4, eventTypeArray)
                statement.setBoolean(5, query.cursor != null)
                statement.setTimestamp(6, query.cursor?.occurredAt?.let { Timestamp.from(it) })
                statement.setTimestamp(7, query.cursor?.occurredAt?.let { Timestamp.from(it) })
                statement.setObject(8, query.cursor?.eventId)
                statement.setInt(9, query.limit)
                statement.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(JdbcActivityRowMappers.activityEvent(rs))
                        }
                    }
                }
            }
        }

    override suspend fun getActivitySummary(recipientUserId: UUID): ActivitySummaryRecord =
        dataSource.withConnection { connection ->
            connection.prepareStatement(JdbcActivitySql.ACTIVITY_SUMMARY_QUERY).use { statement ->
                statement.setObject(1, recipientUserId)
                statement.executeQuery().use { rs ->
                    rs.next()
                    JdbcActivityRowMappers.summary(rs)
                }
            }
        }

    override suspend fun markActivityEventsSeen(
        recipientUserId: UUID,
        eventIds: Set<UUID>,
    ): Int =
        withContext(Dispatchers.IO) {
            if (eventIds.isEmpty()) return@withContext 0

            dataSource.connection.use { connection ->
                connection.prepareStatement(JdbcActivitySql.MARK_ACTIVITY_EVENTS_SEEN_QUERY).use { statement ->
                    val eventIdArray = connection.createArrayOf("uuid", eventIds.toTypedArray())
                    statement.setObject(1, recipientUserId)
                    statement.setArray(2, eventIdArray)
                    statement.executeUpdate()
                }
            }
        }

    override suspend fun dismissActivityEvent(
        recipientUserId: UUID,
        eventId: UUID,
    ): Boolean =
        dataSource.withConnection { connection ->
            connection.prepareStatement(JdbcActivitySql.DISMISS_ACTIVITY_EVENT_QUERY).use { statement ->
                statement.setObject(1, eventId)
                statement.setObject(2, recipientUserId)
                statement.executeUpdate() > 0
            }
        }

    override suspend fun getAlbumRequestEvent(
        recipientUserId: UUID,
        eventId: UUID,
    ): AlbumRequestEventRecord? =
        dataSource.withConnection { connection ->
            connection.prepareStatement(JdbcActivitySql.GET_ALBUM_REQUEST_EVENT_QUERY).use { statement ->
                statement.setObject(1, eventId)
                statement.setObject(2, recipientUserId)
                statement.executeQuery().use { rs ->
                    if (rs.next()) {
                        AlbumRequestEventRecord(
                            eventId = rs.getObject("id", UUID::class.java),
                            ownerUserId = rs.getObject("recipient_user_id", UUID::class.java),
                            requesterUserId = rs.getObject("actor_user_id", UUID::class.java),
                        )
                    } else {
                        null
                    }
                }
            }
        }
}
