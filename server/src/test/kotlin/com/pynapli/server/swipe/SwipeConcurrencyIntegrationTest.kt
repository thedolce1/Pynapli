package com.pynapli.server.swipe

import com.pynapli.server.grid.insertDiscoverableUser
import com.pynapli.server.subscriptions.JdbcSubscriptionRepository
import com.pynapli.server.subscriptions.SubscriptionService
import com.pynapli.server.testsupport.PynapliTestDatabase
import com.pynapli.server.testsupport.raceTogether
import com.pynapli.shared.network.dto.SubscriptionTier
import com.pynapli.shared.network.dto.SwipeAction
import com.pynapli.shared.network.dto.SwipeActionRequestDto
import com.pynapli.shared.network.dto.SwipeDeckRequestDto
import io.kotest.core.spec.style.StringSpec
import kotlinx.coroutines.runBlocking
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.assertEquals

/**
 * Two requests that land at the same moment must leave the same database state as if they had
 * arrived one after the other. Each case repeats the race with fresh users, because a single run
 * can pass by luck; the assertion is on how many iterations ended in a wrong state.
 */
class SwipeConcurrencyIntegrationTest :
    StringSpec({
        val dataSource = PynapliTestDatabase.dataSource
        val iterations = 40

        beforeTest {
            PynapliTestDatabase.reset()
        }

        fun swipeService() =
            SwipeService(
                repository = JdbcSwipeRepository(dataSource),
                subscriptionService = SubscriptionService(JdbcSubscriptionRepository(dataSource)),
            )

        // Each iteration gets its own spot on the map, so earlier iterations' users never show up
        // in a later iteration's deck (the Swipe radius is 100 km; 2 degrees of longitude is more).
        fun seedPair(iteration: Int): Pair<UUID, UUID> {
            val viewer = UUID.randomUUID()
            val target = UUID.randomUUID()
            val longitude = -150.0 + iteration * 2.0
            dataSource.insertDiscoverableUser(viewer, "Viewer", 35, longitude, 43.65)
            dataSource.insertDiscoverableUser(target, "Target", 34, longitude + 0.001, 43.651)
            return viewer to target
        }

        "two people liking each other at the same moment get exactly one match" {
            runBlocking {
                val repository = JdbcSwipeRepository(dataSource)
                var wrong = 0
                repeat(iterations) { iteration ->
                    val (a, b) = seedPair(iteration)
                    raceTogether(
                        { repository.recordSwipeAction(UUID.randomUUID(), a, b, SwipeAction.LIKE) },
                        { repository.recordSwipeAction(UUID.randomUUID(), b, a, SwipeAction.LIKE) },
                    )
                    if (dataSource.countMatchesBetween(a, b) != 1) wrong++
                }
                assertEquals(0, wrong, "iterations out of $iterations with no match after a mutual like")
            }
        }

        "a Pineapple Like retried with the same action id spends exactly one" {
            runBlocking {
                val service = swipeService()
                var wrong = 0
                repeat(iterations) { iteration ->
                    val (viewer, target) = seedPair(iteration)
                    dataSource.grantInventory(viewer, "pineapple_like", 5)
                    val request = service.dealtRequest(viewer, target, SwipeAction.PINEAPPLE_LIKE, UUID.randomUUID())
                    raceTogether({ service.submitAction(viewer, request) }, { service.submitAction(viewer, request) })
                    val left = dataSource.inventoryQuantity(viewer, "pineapple_like")
                    val recorded = dataSource.liveActionCount(viewer, target)
                    if (left != 4 || recorded != 1) wrong++
                }
                assertEquals(0, wrong, "iterations out of $iterations that did not spend exactly one Pineapple Like")
            }
        }

        "two Pineapple Likes on the same person with different action ids spend exactly one" {
            runBlocking {
                val service = swipeService()
                var wrong = 0
                repeat(iterations) { iteration ->
                    val (viewer, target) = seedPair(iteration)
                    dataSource.grantInventory(viewer, "pineapple_like", 5)
                    val first = service.dealtRequest(viewer, target, SwipeAction.PINEAPPLE_LIKE, UUID.randomUUID())
                    val second = first.copy(actionId = UUID.randomUUID().toString())
                    raceTogether({ service.submitAction(viewer, first) }, { service.submitAction(viewer, second) })
                    val left = dataSource.inventoryQuantity(viewer, "pineapple_like")
                    val recorded = dataSource.liveActionCount(viewer, target)
                    if (left != 4 || recorded != 1) wrong++
                }
                assertEquals(0, wrong, "iterations out of $iterations that did not spend exactly one Pineapple Like")
            }
        }

        "an Intro Note retried with the same action id spends exactly one" {
            runBlocking {
                val service = swipeService()
                var wrong = 0
                repeat(iterations) { iteration ->
                    val (viewer, target) = seedPair(iteration)
                    dataSource.insertSocialSubscription(viewer)
                    dataSource.grantInventory(viewer, "intro_note", 5)
                    val request =
                        service
                            .dealtRequest(viewer, target, SwipeAction.INTRO_NOTE, UUID.randomUUID())
                            .copy(noteText = "Hi there")
                    raceTogether({ service.submitAction(viewer, request) }, { service.submitAction(viewer, request) })
                    val left = dataSource.inventoryQuantity(viewer, "intro_note")
                    val recorded = dataSource.liveActionCount(viewer, target)
                    if (left != 4 || recorded != 1) wrong++
                }
                assertEquals(0, wrong, "iterations out of $iterations that did not spend exactly one Intro Note")
            }
        }

        "two Intro Notes to the same person with different action ids spend exactly one" {
            runBlocking {
                val service = swipeService()
                var wrong = 0
                repeat(iterations) { iteration ->
                    val (viewer, target) = seedPair(iteration)
                    dataSource.insertSocialSubscription(viewer)
                    dataSource.grantInventory(viewer, "intro_note", 5)
                    val first =
                        service
                            .dealtRequest(viewer, target, SwipeAction.INTRO_NOTE, UUID.randomUUID())
                            .copy(noteText = "Hi there")
                    val second = first.copy(actionId = UUID.randomUUID().toString())
                    raceTogether({ service.submitAction(viewer, first) }, { service.submitAction(viewer, second) })
                    val left = dataSource.inventoryQuantity(viewer, "intro_note")
                    val recorded = dataSource.liveActionCount(viewer, target)
                    if (left != 4 || recorded != 1) wrong++
                }
                assertEquals(0, wrong, "iterations out of $iterations that did not spend exactly one Intro Note")
            }
        }
    })

private suspend fun SwipeService.dealtRequest(
    viewer: UUID,
    target: UUID,
    action: String,
    actionId: UUID,
): SwipeActionRequestDto {
    val deck = queryDeck(viewer, SwipeDeckRequestDto(limit = 5))
    val card = deck.items.single { it.userId == target.toString() }
    return SwipeActionRequestDto(
        actionId = actionId.toString(),
        deckSessionId = deck.deckSessionId,
        cardToken = card.rankToken,
        targetUserId = target.toString(),
        action = action,
    )
}

private fun DataSource.grantInventory(
    userId: UUID,
    inventoryType: String,
    quantity: Int,
) {
    connection.use { c ->
        c
            .prepareStatement(
                "INSERT INTO premium_inventory (id, user_id, inventory_type, quantity, source) VALUES (?, ?, ?, ?, 'dev')",
            ).use { s ->
                s.setObject(1, UUID.randomUUID())
                s.setObject(2, userId)
                s.setString(3, inventoryType)
                s.setInt(4, quantity)
                s.executeUpdate()
            }
    }
}

private fun DataSource.insertSocialSubscription(userId: UUID) {
    val now = Instant.now()
    connection.use { c ->
        c
            .prepareStatement(
                """
                INSERT INTO subscriptions (
                    id, user_id, platform, external_subscription_id, tier, status,
                    current_period_start, current_period_end
                )
                VALUES (?, ?, 'dev', ?, ?, 'active', ?, ?)
                """.trimIndent(),
            ).use { s ->
                s.setObject(1, UUID.randomUUID())
                s.setObject(2, userId)
                s.setString(3, "race-$userId")
                s.setString(4, SubscriptionTier.SOCIAL)
                s.setTimestamp(5, Timestamp.from(now.minusSeconds(3_600)))
                s.setTimestamp(6, Timestamp.from(now.plusSeconds(86_400)))
                s.executeUpdate()
            }
    }
}

private fun DataSource.inventoryQuantity(
    userId: UUID,
    inventoryType: String,
): Int =
    connection.use { c ->
        c
            .prepareStatement(
                "SELECT COALESCE(sum(quantity), 0)::integer FROM premium_inventory WHERE user_id = ? AND inventory_type = ?",
            ).use { s ->
                s.setObject(1, userId)
                s.setString(2, inventoryType)
                s.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
    }

private fun DataSource.liveActionCount(
    actor: UUID,
    target: UUID,
): Int =
    connection.use { c ->
        c
            .prepareStatement(
                "SELECT COUNT(*) FROM swipe_actions WHERE actor_user_id = ? AND target_user_id = ? AND rewound_at IS NULL",
            ).use { s ->
                s.setObject(1, actor)
                s.setObject(2, target)
                s.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
    }

private fun DataSource.countMatchesBetween(
    a: UUID,
    b: UUID,
): Int =
    connection.use { c ->
        c
            .prepareStatement(
                "SELECT COUNT(*) FROM matches WHERE (user_a = ? AND user_b = ?) OR (user_a = ? AND user_b = ?)",
            ).use { s ->
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
