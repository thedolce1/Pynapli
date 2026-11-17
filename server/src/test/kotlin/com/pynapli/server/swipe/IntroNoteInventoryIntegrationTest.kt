package com.pynapli.server.swipe

import com.pynapli.server.discovery.DiscoveryFilters
import com.pynapli.server.grid.insertDiscoverableUser
import com.pynapli.server.subscriptions.JdbcSubscriptionRepository
import com.pynapli.server.subscriptions.SubscriptionService
import com.pynapli.server.testsupport.PynapliTestDatabase
import com.pynapli.shared.network.dto.SwipeAction
import com.pynapli.shared.network.dto.SwipeActionRequestDto
import com.pynapli.shared.network.dto.SwipeDeckRequestDto
import io.kotest.core.spec.style.StringSpec
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * AUDIT-02: an Intro Note keeps the `premium_inventory` row it spent, like a Pineapple Like, so the
 * spend can be traced and given back when the note is rewound.
 */
class IntroNoteInventoryIntegrationTest :
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
            val viewer = UUID.randomUUID()
            val target = UUID.randomUUID()
            dataSource.insertDiscoverableUser(viewer, "Viewer", 35, -79.38, 43.65)
            dataSource.insertDiscoverableUser(target, "Target", 34, -79.381, 43.651)
            return viewer to target
        }

        "a sent Intro Note records which inventory row paid for it" {
            runBlocking {
                val (viewer, target) = seedPair()
                dataSource.insertSelectSubscription(viewer)
                val inventoryId = dataSource.grantInventoryRow(viewer, "intro_note", 3)
                val service = service()
                val request =
                    service
                        .dealtIntroNoteRequest(viewer, target, UUID.randomUUID())

                assertTrue(service.submitAction(viewer, request).recorded)

                assertEquals(2, dataSource.inventoryRowQuantity(inventoryId))
                assertEquals(inventoryId, dataSource.consumedInventoryId(UUID.fromString(request.actionId)))
            }
        }

        "rewinding an Intro Note gives back the unit it spent" {
            runBlocking {
                val (viewer, target) = seedPair()
                dataSource.insertSelectSubscription(viewer)
                val inventoryId = dataSource.grantInventoryRow(viewer, "intro_note", 3)
                val service = service()
                val request = service.dealtIntroNoteRequest(viewer, target, UUID.randomUUID())
                assertTrue(service.submitAction(viewer, request).recorded)
                assertEquals(2, dataSource.inventoryRowQuantity(inventoryId))

                val rewound =
                    JdbcSwipeRepository(dataSource).rewindIntoFreshCard(
                        actionId = UUID.fromString(request.actionId),
                        viewer = viewer,
                        target = target,
                    )

                assertTrue(rewound)
                assertEquals(3, dataSource.inventoryRowQuantity(inventoryId))
            }
        }
    })

internal suspend fun SwipeService.dealtIntroNoteRequest(
    viewer: UUID,
    target: UUID,
    actionId: UUID,
): SwipeActionRequestDto {
    val deck = queryDeck(viewer, SwipeDeckRequestDto(limit = 5))
    val card = deck.items.single { it.userId == target.toString() }
    return SwipeActionRequestDto(
        actionId = actionId.toString(),
        deckSessionId = deck.deckSessionId,
        cardToken = card.rankToken,
        targetUserId = target.toString(),
        action = SwipeAction.INTRO_NOTE,
        noteText = "Hi there",
    )
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

internal fun DataSource.insertSelectSubscription(userId: UUID) {
    val now = Instant.now()
    connection.use { c ->
        c
            .prepareStatement(
                """
                INSERT INTO subscriptions (
                    id, user_id, platform, external_subscription_id, tier, status,
                    current_period_start, current_period_end
                )
                VALUES (?, ?, 'dev', ?, 'select', 'active', ?, ?)
                """.trimIndent(),
            ).use { s ->
                s.setObject(1, UUID.randomUUID())
                s.setObject(2, userId)
                s.setString(3, "intro-$userId")
                s.setTimestamp(4, java.sql.Timestamp.from(now.minusSeconds(3_600)))
                s.setTimestamp(5, java.sql.Timestamp.from(now.plusSeconds(86_400)))
                s.executeUpdate()
            }
    }
}

internal fun DataSource.grantInventoryRow(
    userId: UUID,
    inventoryType: String,
    quantity: Int,
): UUID {
    val id = UUID.randomUUID()
    connection.use { c ->
        c
            .prepareStatement(
                "INSERT INTO premium_inventory (id, user_id, inventory_type, quantity, source) VALUES (?, ?, ?, ?, 'dev')",
            ).use { s ->
                s.setObject(1, id)
                s.setObject(2, userId)
                s.setString(3, inventoryType)
                s.setInt(4, quantity)
                s.executeUpdate()
            }
    }
    return id
}

internal fun DataSource.inventoryRowQuantity(inventoryId: UUID): Int =
    connection.use { c ->
        c.prepareStatement("SELECT quantity FROM premium_inventory WHERE id = ?").use { s ->
            s.setObject(1, inventoryId)
            s.executeQuery().use { rs ->
                rs.next()
                rs.getInt(1)
            }
        }
    }

internal fun DataSource.consumedInventoryId(actionId: UUID): UUID? =
    connection.use { c ->
        c.prepareStatement("SELECT consumed_inventory_id FROM swipe_actions WHERE id = ?").use { s ->
            s.setObject(1, actionId)
            s.executeQuery().use { rs ->
                rs.next()
                rs.getObject(1, UUID::class.java)
            }
        }
    }
