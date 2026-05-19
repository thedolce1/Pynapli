package com.pynapli.server.activity

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.runBlocking
import org.flywaydb.core.Flyway
import org.junit.After
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ActivityRepositoryIntegrationTest {
    private lateinit var dataSource: HikariDataSource

    @Before
    fun setUp() {
        dataSource = HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = postgres.jdbcUrl
                username = postgres.username
                password = postgres.password
                maximumPoolSize = 2
                poolName = "pynapli-activity-test"
            }
        )

        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .cleanDisabled(false)
            .load()
            .also { flyway ->
                flyway.clean()
                flyway.migrate()
            }
    }

    @After
    fun tearDown() {
        dataSource.close()
    }

    @Test
    fun recordProfileViewWritesEligibleViewInSingleUpsert() = runBlocking {
        val viewer = UUID.fromString("00000000-0000-0000-0000-000000001001")
        val target = UUID.fromString("00000000-0000-0000-0000-000000001002")
        dataSource.insertUserWithDiscovery(viewer)
        dataSource.insertUserWithDiscovery(target)
        val repository = JdbcActivityRepository(dataSource)

        assertTrue(repository.recordProfileView(viewer, target))
        assertTrue(repository.recordProfileView(viewer, target))

        val event = dataSource.singleProfileViewEvent()
        assertEquals(target, event.recipientUserId)
        assertEquals(viewer, event.actorUserId)
        assertEquals(target, event.targetUserId)
        assertEquals(1, event.aggregateCount)
    }

    @Test
    fun recordProfileViewSuppressesIneligibleViewersAndTargets() = runBlocking {
        val target = UUID.fromString("00000000-0000-0000-0000-000000001101")
        val incognitoViewer = UUID.fromString("00000000-0000-0000-0000-000000001102")
        val hiddenFromViewedLists = UUID.fromString("00000000-0000-0000-0000-000000001103")
        val quarantinedViewer = UUID.fromString("00000000-0000-0000-0000-000000001104")
        val inactiveTarget = UUID.fromString("00000000-0000-0000-0000-000000001105")
        val activeViewer = UUID.fromString("00000000-0000-0000-0000-000000001106")
        dataSource.insertUserWithDiscovery(target)
        dataSource.insertUserWithDiscovery(incognitoViewer, incognito = true)
        dataSource.insertUserWithDiscovery(hiddenFromViewedLists, showInViewedLists = false)
        dataSource.insertUserWithDiscovery(quarantinedViewer)
        dataSource.insertUserWithDiscovery(inactiveTarget, status = "paused")
        dataSource.insertUserWithDiscovery(activeViewer)
        dataSource.setRiskState(quarantinedViewer, score = 75, tier = "quarantine")
        val repository = JdbcActivityRepository(dataSource)

        assertFalse(repository.recordProfileView(incognitoViewer, target))
        assertFalse(repository.recordProfileView(hiddenFromViewedLists, target))
        assertFalse(repository.recordProfileView(quarantinedViewer, target))
        assertFalse(repository.recordProfileView(activeViewer, inactiveTarget))

        assertEquals(0, dataSource.profileViewEventCount())
    }

    @Test
    fun listActivityAppliesTabFilterAndCursorPagination() = runBlocking {
        val recipient = UUID.fromString("00000000-0000-0000-0000-000000001201")
        val newestFlirtActor = UUID.fromString("00000000-0000-0000-0000-000000001202")
        val olderFlirtActor = UUID.fromString("00000000-0000-0000-0000-000000001203")
        val viewActor = UUID.fromString("00000000-0000-0000-0000-000000001204")
        dataSource.insertUserWithDiscovery(recipient, displayName = "Recipient")
        dataSource.insertUserWithDiscovery(newestFlirtActor, displayName = "Newest Flirt")
        dataSource.insertUserWithDiscovery(olderFlirtActor, displayName = "Older Flirt")
        dataSource.insertUserWithDiscovery(viewActor, displayName = "Viewer")
        val newestFlirtId = UUID.fromString("00000000-0000-0000-0000-000000001301")
        val olderFlirtId = UUID.fromString("00000000-0000-0000-0000-000000001302")
        dataSource.insertActivityEvent(
            eventId = olderFlirtId,
            recipientUserId = recipient,
            actorUserId = olderFlirtActor,
            eventType = "flirt",
            occurredAt = Instant.parse("2026-05-18T10:00:00Z")
        )
        dataSource.insertActivityEvent(
            eventId = UUID.fromString("00000000-0000-0000-0000-000000001303"),
            recipientUserId = recipient,
            actorUserId = viewActor,
            eventType = "profile_view",
            occurredAt = Instant.parse("2026-05-18T10:30:00Z")
        )
        dataSource.insertActivityEvent(
            eventId = newestFlirtId,
            recipientUserId = recipient,
            actorUserId = newestFlirtActor,
            eventType = "flirt",
            occurredAt = Instant.parse("2026-05-18T11:00:00Z")
        )
        val repository = JdbcActivityRepository(dataSource)

        val firstPage = repository.listActivity(
            ActivityFeedQuery(
                recipientUserId = recipient,
                eventTypes = setOf("flirt"),
                limit = 1,
                cursor = null
            )
        )
        assertEquals(listOf(newestFlirtId), firstPage.map { it.eventId })
        assertEquals(listOf("flirt"), firstPage.map { it.type })

        val secondPage = repository.listActivity(
            ActivityFeedQuery(
                recipientUserId = recipient,
                eventTypes = setOf("flirt"),
                limit = 2,
                cursor = firstPage.single().toCursorAnchor(seenCount = 1)
            )
        )
        assertEquals(listOf(olderFlirtId), secondPage.map { it.eventId })
    }

    @Test
    fun dismissActivityEventSoftDeletesOnlyOwnedEvent() = runBlocking {
        val recipient = UUID.fromString("00000000-0000-0000-0000-000000001401")
        val otherRecipient = UUID.fromString("00000000-0000-0000-0000-000000001402")
        val actor = UUID.fromString("00000000-0000-0000-0000-000000001403")
        val eventId = UUID.fromString("00000000-0000-0000-0000-000000001501")
        dataSource.insertUserWithDiscovery(recipient, displayName = "Recipient")
        dataSource.insertUserWithDiscovery(otherRecipient, displayName = "Other Recipient")
        dataSource.insertUserWithDiscovery(actor, displayName = "Actor")
        dataSource.insertActivityEvent(
            eventId = eventId,
            recipientUserId = recipient,
            actorUserId = actor,
            eventType = "flirt",
            occurredAt = Instant.parse("2026-05-18T12:00:00Z")
        )
        val repository = JdbcActivityRepository(dataSource)

        assertFalse(repository.dismissActivityEvent(otherRecipient, eventId))
        assertEquals(1, repository.listActivity(ActivityFeedQuery(recipient, emptySet(), 10, null)).size)

        assertTrue(repository.dismissActivityEvent(recipient, eventId))
        assertEquals(0, repository.listActivity(ActivityFeedQuery(recipient, emptySet(), 10, null)).size)
    }

    @Test
    fun listActivityExcludesBlockedRelationshipsInSqlFeed() = runBlocking {
        val recipient = UUID.fromString("00000000-0000-0000-0000-000000001601")
        val blockedActor = UUID.fromString("00000000-0000-0000-0000-000000001602")
        val visibleActor = UUID.fromString("00000000-0000-0000-0000-000000001603")
        dataSource.insertUserWithDiscovery(recipient, displayName = "Recipient")
        dataSource.insertUserWithDiscovery(blockedActor, displayName = "Blocked Actor")
        dataSource.insertUserWithDiscovery(visibleActor, displayName = "Visible Actor")
        dataSource.insertActivityEvent(
            eventId = UUID.fromString("00000000-0000-0000-0000-000000001701"),
            recipientUserId = recipient,
            actorUserId = blockedActor,
            eventType = "flirt",
            occurredAt = Instant.parse("2026-05-18T13:00:00Z")
        )
        val visibleEventId = UUID.fromString("00000000-0000-0000-0000-000000001702")
        dataSource.insertActivityEvent(
            eventId = visibleEventId,
            recipientUserId = recipient,
            actorUserId = visibleActor,
            eventType = "flirt",
            occurredAt = Instant.parse("2026-05-18T12:00:00Z")
        )
        dataSource.insertBlock(recipient, blockedActor)
        val repository = JdbcActivityRepository(dataSource)

        val feed = repository.listActivity(ActivityFeedQuery(recipient, emptySet(), 10, null))

        assertEquals(listOf(visibleEventId), feed.map { it.eventId })
    }

    @Test
    fun recordsSwipeMatchAndPartnerInviteEventsIdempotently() = runBlocking {
        val viewer = UUID.fromString("00000000-0000-0000-0000-000000001801")
        val target = UUID.fromString("00000000-0000-0000-0000-000000001802")
        val matchId = UUID.fromString("00000000-0000-0000-0000-000000001803")
        val conversationId = UUID.fromString("00000000-0000-0000-0000-000000001804")
        dataSource.insertUserWithDiscovery(viewer, displayName = "Viewer")
        dataSource.insertUserWithDiscovery(target, displayName = "Target")
        val repository = JdbcActivityRepository(dataSource)

        assertTrue(repository.recordSwipeInterest(viewer, target, "swipe_like"))
        assertFalse(repository.recordSwipeInterest(viewer, target, "swipe_like"))
        assertTrue(repository.recordSwipeInterest(viewer, target, "intro_note"))
        assertTrue(repository.recordMatch(recipientUserId = viewer, actorUserId = target, matchId = matchId))
        assertFalse(repository.recordMatch(recipientUserId = viewer, actorUserId = target, matchId = matchId))
        assertTrue(repository.recordPartnerChatInvite(viewer, target, conversationId))
        assertFalse(repository.recordPartnerChatInvite(viewer, target, conversationId))

        assertEquals(1, dataSource.eventCount("swipe_like"))
        assertEquals(1, dataSource.eventCount("intro_note"))
        assertEquals(1, dataSource.eventCount("match"))
        assertEquals(1, dataSource.eventCount("partner_chat_invite"))
        val matchEvent = repository.listActivity(ActivityFeedQuery(viewer, setOf("match"), 10, null)).single()
        val inviteEvent = repository.listActivity(ActivityFeedQuery(target, setOf("partner_chat_invite"), 10, null)).single()
        assertEquals(matchId.toString(), matchEvent.matchId)
        assertEquals(conversationId.toString(), inviteEvent.conversationId)
    }

    private fun DataSource.insertUserWithDiscovery(
        userId: UUID,
        status: String = "active",
        showInViewedLists: Boolean = true,
        incognito: Boolean = false,
        displayName: String = "User ${userId.toString().takeLast(4)}"
    ) {
        connection.use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO users (id, status)
                VALUES (?, ?)
                """.trimIndent()
            ).use { statement ->
                statement.setObject(1, userId)
                statement.setString(2, status)
                statement.executeUpdate()
            }
            connection.prepareStatement(
                """
                INSERT INTO discovery_settings (
                    user_id,
                    incognito,
                    show_in_viewed_lists
                )
                VALUES (?, ?, ?)
                """.trimIndent()
            ).use { statement ->
                statement.setObject(1, userId)
                statement.setBoolean(2, incognito)
                statement.setBoolean(3, showInViewedLists)
                statement.executeUpdate()
            }
            connection.prepareStatement(
                """
                INSERT INTO profiles (
                    user_id,
                    display_name,
                    moderation_status,
                    visibility_status,
                    profile_completeness_score
                )
                VALUES (?, ?, 'approved', 'visible', 80)
                ON CONFLICT (user_id) DO UPDATE
                SET display_name = EXCLUDED.display_name,
                    moderation_status = EXCLUDED.moderation_status,
                    visibility_status = EXCLUDED.visibility_status,
                    profile_completeness_score = EXCLUDED.profile_completeness_score,
                    updated_at = now()
                """.trimIndent()
            ).use { statement ->
                statement.setObject(1, userId)
                statement.setString(2, displayName)
                statement.executeUpdate()
            }
        }
    }

    private fun DataSource.insertActivityEvent(
        eventId: UUID,
        recipientUserId: UUID,
        actorUserId: UUID,
        eventType: String,
        occurredAt: Instant
    ) {
        connection.use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO activity_events (
                    id,
                    recipient_user_id,
                    actor_user_id,
                    event_type,
                    target_user_id,
                    aggregate_count,
                    first_occurred_at,
                    last_counted_at,
                    occurred_at,
                    updated_at
                )
                VALUES (?, ?, ?, ?, ?, 1, ?, ?, ?, ?)
                """.trimIndent()
            ).use { statement ->
                val occurredAtTimestamp = Timestamp.from(occurredAt)
                statement.setObject(1, eventId)
                statement.setObject(2, recipientUserId)
                statement.setObject(3, actorUserId)
                statement.setString(4, eventType)
                statement.setObject(5, recipientUserId)
                statement.setTimestamp(6, occurredAtTimestamp)
                statement.setTimestamp(7, occurredAtTimestamp)
                statement.setTimestamp(8, occurredAtTimestamp)
                statement.setTimestamp(9, occurredAtTimestamp)
                statement.executeUpdate()
            }
        }
    }

    private fun DataSource.insertBlock(blockerUserId: UUID, blockedUserId: UUID) {
        connection.use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO user_blocks (
                    id,
                    blocker_user_id,
                    blocked_user_id,
                    created_at
                )
                VALUES (?, ?, ?, now())
                """.trimIndent()
            ).use { statement ->
                statement.setObject(1, UUID.randomUUID())
                statement.setObject(2, blockerUserId)
                statement.setObject(3, blockedUserId)
                statement.executeUpdate()
            }
        }
    }

    private fun DataSource.setRiskState(userId: UUID, score: Int, tier: String) {
        connection.use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO account_risk_states (
                    user_id,
                    score,
                    tier,
                    last_event_at,
                    updated_at
                )
                VALUES (?, ?, ?, now(), now())
                ON CONFLICT (user_id) DO UPDATE
                SET score = EXCLUDED.score,
                    tier = EXCLUDED.tier,
                    last_event_at = EXCLUDED.last_event_at,
                    updated_at = EXCLUDED.updated_at
                """.trimIndent()
            ).use { statement ->
                statement.setObject(1, userId)
                statement.setInt(2, score)
                statement.setString(3, tier)
                statement.executeUpdate()
            }
        }
    }

    private fun DataSource.singleProfileViewEvent(): ProfileViewEventRow =
        connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT recipient_user_id, actor_user_id, target_user_id, aggregate_count
                FROM activity_events
                WHERE event_type = 'profile_view'
                """.trimIndent()
            ).use { statement ->
                statement.executeQuery().use { rs ->
                    assertTrue(rs.next())
                    val row = ProfileViewEventRow(
                        recipientUserId = rs.getObject("recipient_user_id", UUID::class.java),
                        actorUserId = rs.getObject("actor_user_id", UUID::class.java),
                        targetUserId = rs.getObject("target_user_id", UUID::class.java),
                        aggregateCount = rs.getInt("aggregate_count")
                    )
                    assertFalse(rs.next())
                    row
                }
            }
        }

    private fun DataSource.profileViewEventCount(): Int =
        connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT count(*)
                FROM activity_events
                WHERE event_type = 'profile_view'
                """.trimIndent()
            ).use { statement ->
                statement.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }

    private fun DataSource.eventCount(eventType: String): Int =
        connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT count(*)
                FROM activity_events
                WHERE event_type = ?
                  AND deleted_at IS NULL
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, eventType)
                statement.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }

    private data class ProfileViewEventRow(
        val recipientUserId: UUID,
        val actorUserId: UUID,
        val targetUserId: UUID,
        val aggregateCount: Int
    )

    companion object {
        private val postgisImage = DockerImageName
            .parse("postgis/postgis:16-3.4")
            .asCompatibleSubstituteFor("postgres")

        private val postgres = PostgreSQLContainer<Nothing>(postgisImage).apply {
            withDatabaseName("pynapli_activity_test")
            withUsername("pynapli")
            withPassword("pynapli_test_password")
        }

        @JvmStatic
        @BeforeClass
        fun startPostgres() {
            postgres.start()
        }

        @JvmStatic
        @AfterClass
        fun stopPostgres() {
            postgres.stop()
        }
    }
}
