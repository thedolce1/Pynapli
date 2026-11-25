package com.pynapli.server.grid

import com.pynapli.server.discovery.DiscoveryFilters
import com.pynapli.server.subscriptions.SubscriptionRecord
import com.pynapli.server.subscriptions.SubscriptionService
import com.pynapli.server.testsupport.PynapliTestDatabase
import com.pynapli.server.testsupport.StaticSubscriptionRepository
import com.pynapli.shared.network.dto.GridQueryRequestDto
import com.pynapli.shared.network.dto.GridQueryResponseDto
import com.pynapli.shared.network.dto.SubscriptionTier
import io.kotest.core.spec.style.StringSpec
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Section 3 end to end on Postgres: 1,300 people around three co-located viewers (Free, Social,
 * Select) who share one rank-cache cell, so the shared exclusion-free window serves all three and
 * locking is applied per viewer after it. The order of every served card, locked or not, is the
 * repository's own rank order: locking never reorders, it only strips identity.
 */
class GridLockedCardsIntegrationTest :
    StringSpec({
        val dataSource = PynapliTestDatabase.dataSource

        beforeTest {
            PynapliTestDatabase.reset()
        }

        val freeViewer = UUID.fromString("00000000-0000-0000-0000-00000000e301")
        val socialViewer = UUID.fromString("00000000-0000-0000-0000-00000000e302")
        val selectViewer = UUID.fromString("00000000-0000-0000-0000-00000000e303")
        val tokens = LockedGridCardTokens("grid-locked-integration-secret")
        val longitude = -122.0841
        val latitude = 37.4220
        val people = 1_300

        fun subscription(
            userId: UUID,
            tier: String,
        ) = SubscriptionRecord(
            subscriptionId = UUID.randomUUID(),
            userId = userId,
            tier = tier,
            status = "active",
            currentPeriodStart = Instant.now().minusSeconds(86_400),
            currentPeriodEnd = Instant.now().plusSeconds(30 * 86_400L),
        )

        val subscriptions = mutableMapOf<UUID, SubscriptionRecord>()

        fun seed() {
            subscriptions.clear()
            subscriptions[socialViewer] = subscription(socialViewer, SubscriptionTier.SOCIAL)
            subscriptions[selectViewer] = subscription(selectViewer, SubscriptionTier.SELECT)
            listOf(freeViewer, socialViewer, selectViewer).forEachIndexed { index, viewer ->
                dataSource.insertDiscoverableUser(viewer, "Viewer $index", age = 38, longitude = longitude, latitude = latitude)
            }
            dataSource.insertDenseDiscoverableUsers(count = people, baseLongitude = longitude, baseLatitude = latitude)
            dataSource.analyzeGridPlannerTables()
        }

        fun service(): GridService =
            GridService(
                repository = JdbcGridRepository(dataSource),
                subscriptionService =
                    SubscriptionService(StaticSubscriptionRepository(own = subscriptions), entitlementCacheTtl = Duration.ZERO),
                lockedCardTokens = tokens,
            )

        // The repository's own rank for [viewer], uncached, paged by keyset: the order to compare to.
        suspend fun rankOrder(viewer: UUID): List<UUID> {
            val repository = JdbcGridRepository(dataSource, rankCacheTtl = Duration.ZERO)
            val ids = mutableListOf<UUID>()
            var cursor: GridCursorAnchor? = null
            do {
                val page = repository.queryNearby(viewer, 50_000, 120, DiscoveryFilters(), cursor)
                ids += page.cards.map { it.userId }
                cursor = page.nextCursorAnchor
            } while (cursor != null)
            return ids
        }

        suspend fun GridService.pageThrough(
            viewer: UUID,
            request: GridQueryRequestDto,
        ): List<GridQueryResponseDto> {
            val pages = mutableListOf(queryGrid(viewer, request))
            while (pages.last().nextCursor != null) {
                check(pages.size < 100) { "paging never ended" }
                pages += queryGrid(viewer, request.copy(cursor = pages.last().nextCursor))
            }
            return pages
        }

        // What the served list must be: real ids before [lockedFrom], that viewer's tokens after it.
        fun expectedIds(
            viewer: UUID,
            order: List<UUID>,
            lockedFrom: Int,
            count: Int,
        ): List<String> =
            order.take(count).mapIndexed { index, id ->
                if (index < lockedFrom) id.toString() else tokens.tokenFor(viewer, id)
            }

        val optIn = GridQueryRequestDto(limit = 60, acceptLockedCards = true)

        "a Free viewer gets 60 unlocked cards then locked cards up to 600, in rank order" {
            runBlocking {
                seed()
                val order = rankOrder(freeViewer)
                assertEquals(people + 2, order.size)

                val pages = service().pageThrough(freeViewer, optIn)
                val cards = pages.flatMap { it.items }

                assertEquals(expectedIds(freeViewer, order, lockedFrom = 60, count = 600), cards.map { it.userId })
                assertTrue(cards.take(60).none { it.locked })
                assertTrue(cards.drop(60).all { it.locked && it.displayName.isEmpty() && it.linkedPartner == null })
                assertTrue(pages.all { it.lockedFromIndex == 60 && it.unlockTier == SubscriptionTier.SOCIAL })
                assertTrue(pages.last().resultLimitReached)
                assertNull(pages.last().nextCursor)
            }
        }

        "a Social viewer gets 600 unlocked cards then locked cards up to 1200; Select sees no lock" {
            runBlocking {
                seed()
                val grid = service()

                val social = grid.pageThrough(socialViewer, optIn).flatMap { it.items }
                assertEquals(expectedIds(socialViewer, rankOrder(socialViewer), lockedFrom = 600, count = 1_200), social.map { it.userId })
                assertEquals(600, social.count { it.locked })

                val selectPages = grid.pageThrough(selectViewer, optIn)
                val select = selectPages.flatMap { it.items }
                assertEquals(rankOrder(selectViewer).map { it.toString() }, select.map { it.userId })
                assertTrue(select.none { it.locked })
                assertTrue(selectPages.all { it.lockedFromIndex == null && it.unlockTier == null && !it.resultLimitReached })
            }
        }

        "an old client without the opt-in still stops at 60 with resultLimitReached" {
            runBlocking {
                seed()
                val order = rankOrder(freeViewer)

                val pages = service().pageThrough(freeViewer, GridQueryRequestDto(limit = 60))

                assertEquals(1, pages.size)
                assertEquals(order.take(60).map { it.toString() }, pages.single().items.map { it.userId })
                assertTrue(pages.single().items.none { it.locked })
                assertTrue(pages.single().resultLimitReached)
                assertEquals(SubscriptionTier.FREE, pages.single().resultLimitTier)
                assertNull(pages.single().lockedFromIndex)
            }
        }

        "an upgrade mid-scroll unlocks the next page under the same cursor" {
            runBlocking {
                seed()
                val order = rankOrder(freeViewer)
                val grid = service()

                val first = grid.queryGrid(freeViewer, optIn)
                val second = grid.queryGrid(freeViewer, optIn.copy(cursor = first.nextCursor))
                assertEquals(expectedIds(freeViewer, order, 60, 120).drop(60), second.items.map { it.userId })

                subscriptions[freeViewer] = subscription(freeViewer, SubscriptionTier.SOCIAL)
                val third = grid.queryGrid(freeViewer, optIn.copy(cursor = second.nextCursor))

                assertEquals(order.subList(120, 180).map { it.toString() }, third.items.map { it.userId })
                assertTrue(third.items.none { it.locked })
                assertNull(third.lockedFromIndex)
            }
        }
    })
