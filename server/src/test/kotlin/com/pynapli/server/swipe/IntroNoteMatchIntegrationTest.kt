package com.pynapli.server.swipe

import com.pynapli.server.grid.insertDiscoverableUser
import com.pynapli.server.psync.targetLikesPartner
import com.pynapli.server.subscriptions.JdbcSubscriptionRepository
import com.pynapli.server.subscriptions.SubscriptionService
import com.pynapli.server.testsupport.PynapliTestDatabase
import com.pynapli.shared.network.dto.LikeBackRequestDto
import com.pynapli.shared.network.dto.SwipeAction
import io.kotest.core.spec.style.StringSpec
import kotlinx.coroutines.runBlocking
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * AUDIT-03: an Intro Note is a positive swipe everywhere a like counts (match in either direction,
 * like-back from Activity, Psync resolution), and anyone holding Intro Notes may send one.
 */
class IntroNoteMatchIntegrationTest :
    StringSpec({
        val dataSource = PynapliTestDatabase.dataSource

        beforeTest {
            PynapliTestDatabase.reset()
        }

        fun service() =
            SwipeService(
                repository = JdbcSwipeRepository(dataSource),
                subscriptionService = SubscriptionService(JdbcSubscriptionRepository(dataSource)),
            )

        fun seedPair(): Pair<UUID, UUID> {
            val a = UUID.randomUUID()
            val b = UUID.randomUUID()
            dataSource.insertDiscoverableUser(a, "Sender", 35, -79.38, 43.65)
            dataSource.insertDiscoverableUser(b, "Receiver", 34, -79.381, 43.651)
            return a to b
        }

        "an Intro Note to someone who already liked you makes the match" {
            runBlocking {
                val (sender, receiver) = seedPair()
                dataSource.insertSelectSubscription(sender)
                dataSource.grantInventoryRow(sender, "intro_note", 3)
                dataSource.insertSwipe(receiver, sender, SwipeAction.LIKE)
                val service = service()

                val response = service.submitAction(sender, service.dealtIntroNoteRequest(sender, receiver, UUID.randomUUID()))

                assertNotNull(response.match, "the note should have matched the earlier like")
                assertEquals(1, dataSource.matchCount(sender, receiver))
            }
        }

        "a like to someone who sent you an Intro Note makes the match" {
            runBlocking {
                val (sender, receiver) = seedPair()
                dataSource.insertSwipe(sender, receiver, SwipeAction.INTRO_NOTE, note = "Hi there")

                val result = JdbcSwipeRepository(dataSource).recordSwipeAction(UUID.randomUUID(), receiver, sender, SwipeAction.LIKE)

                assertNotNull(result.match, "the like should have matched the note")
                assertEquals(1, dataSource.matchCount(sender, receiver))
            }
        }

        "liking back an Intro Note from Activity makes the match" {
            runBlocking {
                val (sender, receiver) = seedPair()
                dataSource.insertSwipe(sender, receiver, SwipeAction.INTRO_NOTE, note = "Hi there")

                val response = service().likeBack(receiver, LikeBackRequestDto(sender.toString()))

                assertNotNull(response.match)
                assertEquals(1, dataSource.matchCount(sender, receiver))
            }
        }

        "Psync counts a target's Intro Note to a partner as liking them" {
            val (target, partner) = seedPair()
            dataSource.insertSwipe(target, partner, SwipeAction.INTRO_NOTE, note = "Hi there")

            val likes = dataSource.connection.use { it.targetLikesPartner(target, partner) }

            assertTrue(likes)
        }

        "a Free user who bought Intro Notes can send one" {
            runBlocking {
                val (sender, receiver) = seedPair()
                val inventoryId = dataSource.grantInventoryRow(sender, "intro_note", 3)
                val service = service()

                val response = service.submitAction(sender, service.dealtIntroNoteRequest(sender, receiver, UUID.randomUUID()))

                assertTrue(response.recorded)
                assertEquals(2, dataSource.inventoryRowQuantity(inventoryId))
            }
        }
    })

private fun DataSource.insertSwipe(
    actor: UUID,
    target: UUID,
    action: String,
    note: String? = null,
) {
    connection.use { c ->
        c
            .prepareStatement(
                "INSERT INTO swipe_actions (id, actor_user_id, target_user_id, action, note_text) VALUES (?, ?, ?, ?, ?)",
            ).use { s ->
                s.setObject(1, UUID.randomUUID())
                s.setObject(2, actor)
                s.setObject(3, target)
                s.setString(4, action)
                s.setString(5, note)
                s.executeUpdate()
            }
    }
}

private fun DataSource.matchCount(
    a: UUID,
    b: UUID,
): Int =
    connection.use { c ->
        c.prepareStatement("SELECT COUNT(*) FROM matches WHERE (user_a = ? AND user_b = ?) OR (user_a = ? AND user_b = ?)").use { s ->
            s.setObject(1, a)
            s.setObject(2, b)
            s.setObject(3, b)
            s.setObject(4, a)
            s.executeQuery().use { rs ->
                rs.next()
                rs.getInt(1)
            }
        }
    }
