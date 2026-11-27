package com.pynapli.server.grid

import com.pynapli.server.discovery.DiscoveryFilters
import com.pynapli.server.safety.RateLimitService
import com.pynapli.server.safety.RateLimitedAction
import com.pynapli.server.subscriptions.SubscriptionService
import com.pynapli.server.testsupport.CountingRateLimitRepository
import com.pynapli.server.testsupport.StaticSubscriptionRepository
import com.pynapli.shared.network.dto.GridQueryRequestDto
import kotlinx.coroutines.runBlocking
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Section 3 S3A-4: pages reaching into the locked window spend a tighter budget (GRID_LOCKED_PAGE,
 * 60 an hour) on top of GRID_QUERY (720 an hour); unlocked pages never touch it.
 */
class GridLockedPageRateLimitTest {
    private val viewer = UUID.fromString("00000000-0000-0000-0000-00000000f101")
    private val lockedKey = "pynapli:rate:${RateLimitedAction.GRID_LOCKED_PAGE}:$viewer"
    private val queryKey = "pynapli:rate:${RateLimitedAction.GRID_QUERY}:$viewer"

    @Test
    fun onlyPagesReachingTheLockedWindowSpendTheLockedBudget() =
        runBlocking {
            val counters = CountingRateLimitRepository()
            val service = freeGrid(counters, totalCards = 1_000)
            val request = GridQueryRequestDto(limit = 25, acceptLockedCards = true)

            // 0-24 and 25-49 are unlocked; 50-74 straddles the depth (60), so it counts.
            val first = service.queryGrid(viewer, request)
            val second = service.queryGrid(viewer, request.copy(cursor = first.nextCursor))
            assertNull(counters.counts[lockedKey])
            val third = service.queryGrid(viewer, request.copy(cursor = second.nextCursor))
            assertEquals(1L, counters.counts[lockedKey])
            assertEquals(10, third.items.count { !it.locked })
            service.queryGrid(viewer, request.copy(cursor = third.nextCursor))
            assertEquals(2L, counters.counts[lockedKey])
            assertEquals(4L, counters.counts[queryKey], "every page still spends GRID_QUERY")
        }

    @Test
    fun theSixtyFirstLockedPageInAnHourIsRateLimitedBeforeAnyRanking() =
        runBlocking {
            val counters = CountingRateLimitRepository()
            val repository = CountingGridRepository(totalCards = 1_000)
            val service =
                GridService(
                    repository,
                    subscriptionService = SubscriptionService(StaticSubscriptionRepository()),
                    rateLimitService = RateLimitService(counters),
                )
            val request = GridQueryRequestDto(limit = 60, acceptLockedCards = true)
            val first = service.queryGrid(viewer, request)
            val lockedCursor = assertNotNull(first.nextCursor)

            repeat(60) {
                val page = service.queryGrid(viewer, request.copy(cursor = lockedCursor))
                assertTrue(page.items.all { it.locked })
            }
            val calls = repository.calls
            val error =
                assertFailsWith<GridException> {
                    service.queryGrid(viewer, request.copy(cursor = lockedCursor))
                }

            assertEquals("RATE_LIMITED", error.code)
            assertEquals(calls, repository.calls, "a refused locked page does no ranking work")
            // Unlocked pages are still served: the locked budget never blocks the free 60.
            assertEquals(60, service.queryGrid(viewer, request).items.size)
        }

    @Test
    fun oldClientsAndPaidUnlockedPagesNeverSpendTheLockedBudget() =
        runBlocking {
            val counters = CountingRateLimitRepository()
            val service = freeGrid(counters, totalCards = 1_000)

            val first = service.queryGrid(viewer, GridQueryRequestDto(limit = 25))
            val second = service.queryGrid(viewer, GridQueryRequestDto(limit = 25, cursor = first.nextCursor))
            service.queryGrid(viewer, GridQueryRequestDto(limit = 25, cursor = second.nextCursor))

            assertNull(counters.counts[lockedKey])
        }

    private fun freeGrid(
        counters: CountingRateLimitRepository,
        totalCards: Int,
    ): GridService =
        GridService(
            CountingGridRepository(totalCards),
            subscriptionService = SubscriptionService(StaticSubscriptionRepository()),
            rateLimitService = RateLimitService(counters),
        )
}

private class CountingGridRepository(
    totalCards: Int,
) : GridRepository {
    var calls = 0
        private set

    private val cards =
        List(totalCards) { index ->
            GridCardRecord(
                userId = UUID.nameUUIDFromBytes("grid-locked-rate-$index".toByteArray()),
                displayName = "Rate $index",
                showDistance = true,
                distanceMeters = index.toDouble(),
                orderBucket = index,
            )
        }

    override suspend fun queryNearby(
        viewerUserId: UUID,
        radiusMeters: Int,
        limit: Int,
        filters: DiscoveryFilters,
        cursor: GridCursorAnchor?,
        hiddenFromViewerIds: Set<UUID>,
    ): GridQueryResult {
        calls++
        val startIndex = cursor?.orderBucket?.plus(1) ?: 0
        val page = cards.drop(startIndex).take(limit)
        val hasMore = cards.size > startIndex + limit
        return GridQueryResult(
            viewerHadLocation = true,
            cards = page,
            nextCursorAnchor = if (hasMore && page.isNotEmpty()) page.last().toCursorAnchor() else null,
        )
    }
}
