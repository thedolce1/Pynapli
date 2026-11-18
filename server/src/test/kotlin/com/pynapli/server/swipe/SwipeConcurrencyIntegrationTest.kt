package com.pynapli.server.swipe

import com.pynapli.server.discovery.DiscoveryFilters
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
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

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

        "a rewind racing the other person's like back ends as either a match or a rewound like, never both" {
            runBlocking {
                val repository = JdbcSwipeRepository(dataSource)
                var wrong = 0
                repeat(iterations) { iteration ->
                    val (viewer, target) = seedPair(iteration)
                    val likeId = UUID.randomUUID()
                    assertTrue(repository.recordSwipeAction(likeId, viewer, target, SwipeAction.LIKE).recorded)
                    raceTogether(
                        { repository.rewindIntoFreshCard(likeId, viewer, target) },
                        { repository.recordSwipeAction(UUID.randomUUID(), target, viewer, SwipeAction.LIKE) },
                    )
                    val matched = dataSource.countMatchesBetween(viewer, target) == 1
                    val likeRewound = dataSource.isRewound(likeId)
                    // Allowed: (match, like still live) or (no match, like rewound).
                    if (matched == likeRewound) wrong++
                }
                assertEquals(0, wrong, "iterations out of $iterations that ended with a match on a rewound like (or neither)")
            }
        }

        "a rewind racing a like-back from Activity ends as either a match or a rewound like, never both" {
            runBlocking {
                val repository = JdbcSwipeRepository(dataSource)
                var wrong = 0
                repeat(iterations) { iteration ->
                    val (viewer, target) = seedPair(iteration)
                    val likeId = UUID.randomUUID()
                    assertTrue(repository.recordSwipeAction(likeId, viewer, target, SwipeAction.LIKE).recorded)
                    raceTogether(
                        { repository.rewindIntoFreshCard(likeId, viewer, target) },
                        { repository.likeBack(UUID.randomUUID(), target, viewer, travelLocationId = null) },
                    )
                    val matched = dataSource.countMatchesBetween(viewer, target) == 1
                    val likeRewound = dataSource.isRewound(likeId)
                    if (matched == likeRewound) wrong++
                }
                assertEquals(0, wrong, "iterations out of $iterations that ended with a match on a rewound like (or neither)")
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

        "a Pineapple Like with none left is refused, records nothing, and leaves the card to act on" {
            runBlocking {
                val service = swipeService()
                val (viewer, target) = seedPair(0)
                val request = service.dealtRequest(viewer, target, SwipeAction.PINEAPPLE_LIKE, UUID.randomUUID())

                val refused = assertFailsWith<SwipeException> { service.submitAction(viewer, request) }

                assertEquals("PINEAPPLE_LIKE_INVENTORY_REQUIRED", refused.code)
                assertEquals(0, dataSource.liveActionCount(viewer, target))
                // The refused swipe did not use up the card: a plain Like on it still lands.
                val like = request.copy(actionId = UUID.randomUUID().toString(), action = SwipeAction.LIKE)
                assertTrue(service.submitAction(viewer, like).recorded)
            }
        }

        "a Pineapple Like spent in the record transaction is refunded when it is rewound" {
            runBlocking {
                val service = swipeService()
                val (viewer, target) = seedPair(0)
                dataSource.insertSocialSubscription(viewer) // Rewind is a paid feature
                dataSource.grantInventory(viewer, "pineapple_like", 5)
                val request = service.dealtRequest(viewer, target, SwipeAction.PINEAPPLE_LIKE, UUID.randomUUID())

                assertTrue(service.submitAction(viewer, request).recorded)
                assertEquals(4, dataSource.inventoryQuantity(viewer, "pineapple_like"))
                assertTrue(service.rewind(viewer).rewound)

                assertEquals(5, dataSource.inventoryQuantity(viewer, "pineapple_like"))
            }
        }

        "a Pineapple Like with a note retried with the same action id spends exactly one" {
            runBlocking {
                val service = swipeService()
                var wrong = 0
                repeat(iterations) { iteration ->
                    val (viewer, target) = seedPair(iteration)
                    dataSource.grantInventory(viewer, "pineapple_like", 5)
                    val request =
                        service
                            .dealtRequest(viewer, target, SwipeAction.PINEAPPLE_LIKE, UUID.randomUUID())
                            .copy(noteText = "Hi there")
                    raceTogether({ service.submitAction(viewer, request) }, { service.submitAction(viewer, request) })
                    val left = dataSource.inventoryQuantity(viewer, "pineapple_like")
                    val recorded = dataSource.liveActionCount(viewer, target)
                    if (left != 4 || recorded != 1) wrong++
                }
                assertEquals(0, wrong, "iterations out of $iterations that did not spend exactly one Pineapple Like with a note")
            }
        }

        "two Pineapple Likes with notes to the same person with different action ids spend exactly one" {
            runBlocking {
                val service = swipeService()
                var wrong = 0
                repeat(iterations) { iteration ->
                    val (viewer, target) = seedPair(iteration)
                    dataSource.grantInventory(viewer, "pineapple_like", 5)
                    val first =
                        service
                            .dealtRequest(viewer, target, SwipeAction.PINEAPPLE_LIKE, UUID.randomUUID())
                            .copy(noteText = "Hi there")
                    val second = first.copy(actionId = UUID.randomUUID().toString())
                    raceTogether({ service.submitAction(viewer, first) }, { service.submitAction(viewer, second) })
                    val left = dataSource.inventoryQuantity(viewer, "pineapple_like")
                    val recorded = dataSource.liveActionCount(viewer, target)
                    if (left != 4 || recorded != 1) wrong++
                }
                assertEquals(0, wrong, "iterations out of $iterations that did not spend exactly one Pineapple Like with a note")
            }
        }
    })

internal suspend fun SwipeService.dealtRequest(
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

internal fun DataSource.grantInventory(
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

internal fun DataSource.inventoryQuantity(
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

internal fun DataSource.liveActionCount(
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

private fun DataSource.isRewound(actionId: UUID): Boolean =
    connection.use { c ->
        c.prepareStatement("SELECT rewound_at IS NOT NULL FROM swipe_actions WHERE id = ?").use { s ->
            s.setObject(1, actionId)
            s.executeQuery().use { rs ->
                rs.next()
                rs.getBoolean(1)
            }
        }
    }

/** Rewinds [actionId] straight through the repository, re-dealing the card the way Rewind does. */
internal suspend fun SwipeRepository.rewindIntoFreshCard(
    actionId: UUID,
    viewer: UUID,
    target: UUID,
): Boolean {
    val codec = SwipeDeckTokenCodec()
    val now = Instant.now()
    val filters = DiscoveryFilters()
    val scopeHash = SwipeDeckTokenCodec.deckScopeHash(viewer, SWIPE_MAX_RADIUS_METERS, 1, filters)
    val session = codec.issueDeckSession(viewerUserId = viewer, scopeHash = scopeHash, now = now)
    val cardToken =
        codec.issueCardToken(
            deckSessionId = session.deckSessionId,
            viewerUserId = viewer,
            targetUserId = target,
            scopeHash = scopeHash,
            generatedAt = session.generatedAt,
            expiresAt = session.expiresAt,
            position = 0,
        )
    return rewindSwipeAction(
        actionId = actionId,
        actorUserId = viewer,
        targetUserId = target,
        session =
            session.toSwipeDeckSessionRecord(
                viewerUserId = viewer,
                scopeHash = scopeHash,
                radiusMeters = SWIPE_MAX_RADIUS_METERS,
                pageLimit = 1,
                filterPayload = filters.toPersistedJson(),
            ),
        card =
            SwipeDeckSessionCardRecord(
                cardId = UUID.randomUUID(),
                sessionId = session.sessionId,
                viewerUserId = viewer,
                targetUserId = target,
                cardTokenHash = codec.tokenHash(cardToken),
                position = 0,
                dealtAt = now,
            ),
        rewoundAt = now,
    )
}

internal fun DataSource.countMatchesBetween(
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
