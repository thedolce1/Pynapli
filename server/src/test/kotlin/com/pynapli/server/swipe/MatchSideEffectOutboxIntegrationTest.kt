package com.pynapli.server.swipe

import com.pynapli.server.activity.ActivityService
import com.pynapli.server.activity.JdbcActivityRepository
import com.pynapli.server.grid.insertDiscoverableUser
import com.pynapli.server.messaging.JdbcMessagingRepository
import com.pynapli.server.messaging.MessagingService
import com.pynapli.server.notifications.NotificationEventDraft
import com.pynapli.server.notifications.NotificationKind
import com.pynapli.server.notifications.NotificationOutboxRecord
import com.pynapli.server.notifications.NotificationPublisher
import com.pynapli.server.testsupport.PynapliTestDatabase
import com.pynapli.shared.network.dto.LikeBackRequestDto
import com.pynapli.shared.network.dto.SwipeAction
import io.kotest.core.spec.style.StringSpec
import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * A match's activity rows, conversation and push are recorded in the same transaction as the match
 * (the match side-effect outbox) and delivered right after commit. A crash between the commit and
 * that delivery must not lose them: the drainer delivers them later, exactly once.
 */
class MatchSideEffectOutboxIntegrationTest :
    StringSpec({
        val dataSource = PynapliTestDatabase.dataSource

        beforeTest {
            PynapliTestDatabase.reset()
        }

        class RecordingPublisher : NotificationPublisher {
            val events = CopyOnWriteArrayList<NotificationEventDraft>()

            override suspend fun enqueue(notification: NotificationEventDraft): NotificationOutboxRecord? {
                events += notification
                return null
            }
        }

        class Harness {
            val publisher = RecordingPublisher()
            val activity = ActivityService(JdbcActivityRepository(dataSource))
            val messaging = MessagingService(JdbcMessagingRepository(dataSource))
            val delivery = MatchSideEffectDelivery(activity, publisher, messaging.matchConversationOpener)

            fun drainer(clock: Clock) = MatchSideEffectDrainer(dataSource, delivery, clock)

            fun service() =
                SwipeService(
                    repository = JdbcSwipeRepository(dataSource),
                    notificationPublisher = publisher,
                    activityService = activity,
                    matchConversationOpener = messaging.matchConversationOpener,
                )
        }

        // Past the fast path's lease, so the drainer may pick up whatever is still pending.
        val later = Clock.offset(Clock.systemUTC(), Duration.ofMinutes(5))

        fun seedPair(): Pair<UUID, UUID> {
            val a = UUID.randomUUID()
            val b = UUID.randomUUID()
            dataSource.insertDiscoverableUser(a, "Avery", 35, -79.38, 43.65)
            dataSource.insertDiscoverableUser(b, "Blake", 34, -79.381, 43.651)
            return a to b
        }

        fun assertExactlyOneOfEach(
            harness: Harness,
            a: UUID,
            b: UUID,
        ) {
            assertEquals(1, dataSource.activityRows(recipient = b, actor = a, eventType = "swipe_like"), "a's like in b's Likes You")
            assertEquals(1, dataSource.activityRows(recipient = a, actor = b, eventType = "swipe_like"), "b's like in a's Likes You")
            assertEquals(1, dataSource.activityRows(recipient = a, actor = b, eventType = "match"), "match row for a")
            assertEquals(1, dataSource.activityRows(recipient = b, actor = a, eventType = "match"), "match row for b")
            assertEquals(1, dataSource.matchConversationsBetween(a, b), "match conversation")
            assertEquals(1, harness.publisher.events.count { it.kind == NotificationKind.MATCH }, "match push")
        }

        "a match committed before a crash still gets its activity, conversation and push from the drainer" {
            runBlocking {
                val harness = Harness()
                val (a, b) = seedPair()
                val repository = JdbcSwipeRepository(dataSource)
                // The two likes commit, then the process dies before any side effect runs.
                repository.recordSwipeAction(UUID.randomUUID(), a, b, SwipeAction.LIKE)
                val match = repository.recordSwipeAction(UUID.randomUUID(), b, a, SwipeAction.LIKE).match
                assertNotNull(match)
                assertEquals(0, dataSource.activityRows(recipient = a, actor = b, eventType = "match"))
                assertEquals(3, dataSource.pendingSideEffects())

                val drained = harness.drainer(later).drainOnce()

                assertEquals(3, drained.delivered)
                assertExactlyOneOfEach(harness, a, b)
                assertEquals(0, dataSource.pendingSideEffects())
                // A second pass finds nothing left to do.
                assertEquals(0, harness.drainer(later).drainOnce().delivered)
                assertExactlyOneOfEach(harness, a, b)
            }
        }

        "the drainer leaves a just-committed match to the request that made it" {
            runBlocking {
                val harness = Harness()
                val (a, b) = seedPair()
                val repository = JdbcSwipeRepository(dataSource)
                repository.recordSwipeAction(UUID.randomUUID(), a, b, SwipeAction.LIKE)
                repository.recordSwipeAction(UUID.randomUUID(), b, a, SwipeAction.LIKE)

                // Still inside the fast path's lease: the request that committed it is delivering now.
                assertEquals(0, harness.drainer(Clock.systemUTC()).drainOnce().delivered)
                assertEquals(3, dataSource.pendingSideEffects())
            }
        }

        "the fast path delivers at once and the drainer never repeats it" {
            runBlocking {
                val harness = Harness()
                val (a, b) = seedPair()
                val service = harness.service()

                service.submitAction(a, service.dealtRequest(a, b, SwipeAction.LIKE, UUID.randomUUID()))
                val response = service.submitAction(b, service.dealtRequest(b, a, SwipeAction.LIKE, UUID.randomUUID()))
                assertNotNull(response.match)

                // Delivered inside the request: no wait for a worker.
                assertExactlyOneOfEach(harness, a, b)
                assertEquals(0, dataSource.pendingSideEffects())

                assertEquals(0, harness.drainer(later).drainOnce().delivered)
                assertExactlyOneOfEach(harness, a, b)
            }
        }

        "a like-back match committed before a crash is delivered by the drainer" {
            runBlocking {
                val harness = Harness()
                val (a, b) = seedPair()
                val repository = JdbcSwipeRepository(dataSource)
                repository.recordSwipeAction(UUID.randomUUID(), b, a, SwipeAction.LIKE)
                // b's like went through the full request, so only the like-back is left pending.
                harness.drainer(later).drainOnce()
                assertNotNull(repository.likeBack(UUID.randomUUID(), a, b, travelLocationId = null)?.match)

                harness.drainer(later).drainOnce()

                assertExactlyOneOfEach(harness, a, b)
            }
        }

        "a like-back through the service delivers once and leaves nothing for the drainer" {
            runBlocking {
                val harness = Harness()
                val (a, b) = seedPair()
                val service = harness.service()
                service.submitAction(b, service.dealtRequest(b, a, SwipeAction.LIKE, UUID.randomUUID()))

                assertNotNull(service.likeBack(a, LikeBackRequestDto(b.toString())).match)

                assertEquals(0, harness.drainer(later).drainOnce().delivered)
                assertExactlyOneOfEach(harness, a, b)
            }
        }
    })

private fun DataSource.activityRows(
    recipient: UUID,
    actor: UUID,
    eventType: String,
): Int =
    connection.use { c ->
        c
            .prepareStatement(
                "SELECT COUNT(*) FROM activity_events WHERE recipient_user_id = ? AND actor_user_id = ? AND event_type = ? AND deleted_at IS NULL",
            ).use { s ->
                s.setObject(1, recipient)
                s.setObject(2, actor)
                s.setString(3, eventType)
                s.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
    }

private fun DataSource.matchConversationsBetween(
    a: UUID,
    b: UUID,
): Int =
    connection.use { c ->
        c
            .prepareStatement(
                """
                SELECT COUNT(*) FROM conversations c
                WHERE c.origin = 'swipe'
                  AND EXISTS (SELECT 1 FROM conversation_participants p WHERE p.conversation_id = c.id AND p.user_id = ?)
                  AND EXISTS (SELECT 1 FROM conversation_participants p WHERE p.conversation_id = c.id AND p.user_id = ?)
                """.trimIndent(),
            ).use { s ->
                s.setObject(1, a)
                s.setObject(2, b)
                s.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
    }

private fun DataSource.pendingSideEffects(): Int =
    connection.use { c ->
        c.prepareStatement("SELECT COUNT(*) FROM match_side_effect_outbox WHERE delivered_at IS NULL").use { s ->
            s.executeQuery().use { rs ->
                rs.next()
                rs.getInt(1)
            }
        }
    }
