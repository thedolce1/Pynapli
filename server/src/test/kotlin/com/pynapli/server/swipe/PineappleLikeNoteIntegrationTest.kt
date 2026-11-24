package com.pynapli.server.swipe

import com.pynapli.server.activity.ActivityFeedQuery
import com.pynapli.server.activity.JdbcActivityRepository
import com.pynapli.server.grid.insertDiscoverableUser
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
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A Pineapple Like can carry a short note (Intro Notes merged into it, 2026-11-17). The note never
 * changes what the Pineapple Like is: a positive swipe that spends one unit and matches like any
 * other like.
 */
class PineappleLikeNoteIntegrationTest :
    StringSpec({
        val dataSource = PynapliTestDatabase.dataSource

        beforeTest {
            PynapliTestDatabase.reset()
        }

        fun swipeService() =
            SwipeService(
                repository = JdbcSwipeRepository(dataSource),
                subscriptionService = SubscriptionService(JdbcSubscriptionRepository(dataSource)),
            )

        fun seedPair(): Pair<UUID, UUID> {
            val a = UUID.randomUUID()
            val b = UUID.randomUUID()
            dataSource.insertDiscoverableUser(a, "Avery", 35, -79.38, 43.65)
            dataSource.insertDiscoverableUser(b, "Blake", 34, -79.381, 43.651)
            return a to b
        }

        "a Pineapple Like with a note becomes a match when the other person likes back afterwards" {
            runBlocking {
                val service = swipeService()
                val (a, b) = seedPair()
                dataSource.grantInventory(a, "pineapple_like", 2)

                val noted =
                    service
                        .dealtRequest(a, b, SwipeAction.PINEAPPLE_LIKE, UUID.randomUUID())
                        .copy(noteText = "  Loved your hiking photos  ")
                val first = service.submitAction(a, noted)
                assertTrue(first.recorded)
                assertNull(first.match)
                assertEquals(1, dataSource.inventoryQuantity(a, "pineapple_like"))
                assertEquals("Loved your hiking photos", dataSource.noteOf(a, b))

                val likeBack = service.submitAction(b, service.dealtRequest(b, a, SwipeAction.LIKE, UUID.randomUUID()))
                assertNotNull(likeBack.match)
                assertEquals(1, dataSource.countMatchesBetween(a, b))
            }
        }

        "a Pineapple Like with a note becomes a match when the other person had already liked" {
            runBlocking {
                val service = swipeService()
                val (a, b) = seedPair()
                dataSource.grantInventory(a, "pineapple_like", 1)

                assertNull(service.submitAction(b, service.dealtRequest(b, a, SwipeAction.LIKE, UUID.randomUUID())).match)
                val noted =
                    service
                        .dealtRequest(a, b, SwipeAction.PINEAPPLE_LIKE, UUID.randomUUID())
                        .copy(noteText = "Coffee this week?")
                val response = service.submitAction(a, noted)

                assertNotNull(response.match)
                assertEquals(1, dataSource.countMatchesBetween(a, b))
                assertEquals(0, dataSource.inventoryQuantity(a, "pineapple_like"))
            }
        }

        "liking back a noted Pineapple Like from Activity makes the match" {
            runBlocking {
                val service = swipeService()
                val (a, b) = seedPair()
                dataSource.grantInventory(a, "pineapple_like", 1)
                service.submitAction(
                    a,
                    service.dealtRequest(a, b, SwipeAction.PINEAPPLE_LIKE, UUID.randomUUID()).copy(noteText = "Hi!"),
                )

                val response = service.likeBack(b, LikeBackRequestDto(targetUserId = a.toString()))

                assertNotNull(response.match)
                assertEquals(1, dataSource.countMatchesBetween(a, b))
            }
        }

        "the recipient sees the note on the Pineapple Like in Activity" {
            runBlocking {
                val service = swipeService()
                val activity =
                    JdbcActivityRepository(
                        dataSource,
                        contactBlockTokens =
                            com.pynapli.server.contactblocks.ContactBlockTokens
                                .localDevelopment(),
                    )
                val (a, b) = seedPair()
                dataSource.grantInventory(a, "pineapple_like", 1)
                service.submitAction(
                    a,
                    service.dealtRequest(a, b, SwipeAction.PINEAPPLE_LIKE, UUID.randomUUID()).copy(noteText = "Hi!"),
                )
                assertTrue(activity.recordSwipeInterest(a, b, "pineapple_like"))

                val items = activity.listActivity(ActivityFeedQuery(b, setOf("pineapple_like"), limit = 10, cursor = null))

                assertEquals(listOf("Hi!"), items.map { it.noteText })
            }
        }

        "notes turned off: the note is refused, nothing is spent, and the Pineapple Like alone still lands" {
            runBlocking {
                val service = swipeService()
                val (a, b) = seedPair()
                dataSource.grantInventory(a, "pineapple_like", 1)
                dataSource.setAllowsPineappleLikeNotes(b, false)
                val noted =
                    service
                        .dealtRequest(a, b, SwipeAction.PINEAPPLE_LIKE, UUID.randomUUID())
                        .copy(noteText = "Hey")

                val refused = assertFailsWith<SwipeException> { service.submitAction(a, noted) }

                assertEquals("PINEAPPLE_LIKE_NOTE_NOT_ACCEPTED", refused.code)
                assertEquals(1, dataSource.inventoryQuantity(a, "pineapple_like"))
                assertEquals(0, dataSource.liveActionCount(a, b))
                val plain = noted.copy(actionId = UUID.randomUUID().toString(), noteText = null)
                assertTrue(service.submitAction(a, plain).recorded)
                assertEquals(0, dataSource.inventoryQuantity(a, "pineapple_like"))
            }
        }

        "a note longer than 140 characters is refused before anything is spent" {
            runBlocking {
                val service = swipeService()
                val (a, b) = seedPair()
                dataSource.grantInventory(a, "pineapple_like", 1)
                val noted =
                    service
                        .dealtRequest(a, b, SwipeAction.PINEAPPLE_LIKE, UUID.randomUUID())
                        .copy(noteText = "x".repeat(141))

                val refused = assertFailsWith<SwipeException> { service.submitAction(a, noted) }

                assertEquals("PINEAPPLE_LIKE_NOTE_TOO_LONG", refused.code)
                assertEquals(1, dataSource.inventoryQuantity(a, "pineapple_like"))
            }
        }

        "V92 turns legacy Intro Notes into Pineapple Likes with their note and converts unspent Intro Note inventory" {
            val (a, b) = seedPair()
            val migration =
                checkNotNull(javaClass.classLoader.getResource("db/migration/V92__merge_intro_notes_into_pineapple_likes.sql"))
                    .readText()
            dataSource.connection.use { connection ->
                connection.autoCommit = false
                try {
                    connection.createStatement().use { statement ->
                        // Recreate the pre-V92 world inside this transaction, then replay V92 over it.
                        statement.execute("ALTER TABLE swipe_actions DROP CONSTRAINT swipe_actions_action_check")
                        statement.execute("ALTER TABLE swipe_actions DROP CONSTRAINT swipe_actions_note_text_check")
                        statement.execute("ALTER TABLE swipe_deck_cards DROP CONSTRAINT swipe_deck_cards_action_check")
                        statement.execute("ALTER TABLE premium_inventory DROP CONSTRAINT premium_inventory_type_check")
                        statement.execute(
                            """
                            INSERT INTO swipe_actions (id, actor_user_id, target_user_id, action, note_text)
                            VALUES ('${UUID.randomUUID()}', '$a', '$b', 'intro_note', 'Legacy hello')
                            """.trimIndent(),
                        )
                        statement.execute(
                            """
                            INSERT INTO premium_inventory (id, user_id, inventory_type, quantity, source)
                            VALUES ('${UUID.randomUUID()}', '$a', 'intro_note', 2, 'purchase')
                            """.trimIndent(),
                        )
                        statement.execute(
                            """
                            INSERT INTO activity_events (id, recipient_user_id, actor_user_id, event_type, target_user_id)
                            VALUES ('${UUID.randomUUID()}', '$b', '$a', 'intro_note', '$b')
                            """.trimIndent(),
                        )
                        statement.execute(migration)
                        statement.executeQuery("SELECT action, note_text FROM swipe_actions WHERE actor_user_id = '$a'").use { rs ->
                            assertTrue(rs.next())
                            assertEquals("pineapple_like", rs.getString(1))
                            assertEquals("Legacy hello", rs.getString(2))
                        }
                        statement.executeQuery("SELECT inventory_type, quantity FROM premium_inventory WHERE user_id = '$a'").use { rs ->
                            assertTrue(rs.next())
                            assertEquals("pineapple_like", rs.getString(1))
                            assertEquals(2, rs.getInt(2))
                        }
                        statement.executeQuery("SELECT event_type FROM activity_events WHERE actor_user_id = '$a'").use { rs ->
                            assertTrue(rs.next())
                            assertEquals("pineapple_like", rs.getString(1))
                        }
                        // Nothing can write a new Intro Note after V92.
                        assertTrue(
                            runCatching {
                                statement.execute(
                                    "INSERT INTO swipe_actions (id, actor_user_id, target_user_id, action) " +
                                        "VALUES ('${UUID.randomUUID()}', '$b', '$a', 'intro_note')",
                                )
                            }.isFailure,
                            "intro_note is no longer a valid swipe action",
                        )
                    }
                } finally {
                    connection.rollback()
                    connection.autoCommit = true
                }
            }
        }
    })

private fun DataSource.noteOf(
    actor: UUID,
    target: UUID,
): String? =
    connection.use { c ->
        c
            .prepareStatement(
                "SELECT note_text FROM swipe_actions WHERE actor_user_id = ? AND target_user_id = ? AND rewound_at IS NULL",
            ).use { s ->
                s.setObject(1, actor)
                s.setObject(2, target)
                s.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
            }
    }

private fun DataSource.setAllowsPineappleLikeNotes(
    userId: UUID,
    allowed: Boolean,
) {
    connection.use { c ->
        c.prepareStatement("UPDATE discovery_settings SET allow_intro_notes = ? WHERE user_id = ?").use { s ->
            s.setBoolean(1, allowed)
            s.setObject(2, userId)
            s.executeUpdate()
        }
    }
}
