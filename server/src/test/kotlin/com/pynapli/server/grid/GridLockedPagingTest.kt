package com.pynapli.server.grid

import com.pynapli.server.discovery.DiscoveryFilters
import com.pynapli.server.subscriptions.SubscriptionRecord
import com.pynapli.server.subscriptions.SubscriptionService
import com.pynapli.server.testsupport.StaticSubscriptionRepository
import com.pynapli.shared.network.dto.GridCardDto
import com.pynapli.shared.network.dto.GridQueryRequestDto
import com.pynapli.shared.network.dto.GridQueryResponseDto
import com.pynapli.shared.network.dto.SubscriptionTier
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Section 3 S3A-1: past the tier depth an opted-in client keeps paging into locked cards up to the
 * next tier's depth (Free 61-600, Social 601-1200, Select never), decided per page from the sealed
 * seen count and the entitlement read on that request. Old clients keep stopping at the depth.
 */
class GridLockedPagingTest {
    private val viewer = UUID.fromString("00000000-0000-0000-0000-00000000f001")

    @Test
    fun freeViewerGetsSixtyUnlockedThenLockedCardsUpToSixHundred() =
        runBlocking {
            val repository = SequentialGridRepository(totalCards = 1_000)
            val service = GridService(repository, subscriptionService = tierService(SubscriptionTier.FREE))

            val pages = service.pageThrough(GridQueryRequestDto(limit = 60, acceptLockedCards = true))
            val cards = pages.flatMap { it.items }

            assertEquals(600, cards.size)
            assertEquals(List(60) { repository.idAt(it).toString() }, cards.take(60).map { it.userId })
            assertTrue(cards.take(60).none { it.locked })
            assertTrue(cards.drop(60).all { it.locked })
            assertLockedCardsCarryNoIdentity(cards.drop(60), repository)
            // The first page ends exactly at the depth and its next page starts locked: it says so.
            assertEquals(60, pages.first().lockedFromIndex)
            pages.forEach { page ->
                assertEquals(60, page.lockedFromIndex)
                assertEquals(SubscriptionTier.SOCIAL, page.unlockTier)
            }
            val last = pages.last()
            assertNull(last.nextCursor)
            assertTrue(last.resultLimitReached, "the end of the locked window is the end of results")
            assertEquals(SubscriptionTier.FREE, last.resultLimitTier)
            assertTrue(pages.dropLast(1).none { it.resultLimitReached })
        }

    @Test
    fun socialViewerGetsSixHundredUnlockedThenLockedUpToTwelveHundredWithAStraddlingPage() =
        runBlocking {
            val repository = SequentialGridRepository(totalCards = 1_500)
            val service = GridService(repository, subscriptionService = tierService(SubscriptionTier.SOCIAL))

            val pages = service.pageThrough(GridQueryRequestDto(limit = 90, acceptLockedCards = true))
            val cards = pages.flatMap { it.items }

            assertEquals(1_200, cards.size)
            assertTrue(cards.take(600).none { it.locked })
            assertTrue(cards.drop(600).all { it.locked })
            assertLockedCardsCarryNoIdentity(cards.drop(600), repository)
            // 600 is not a multiple of 90: the seventh page holds 60 unlocked then 30 locked cards.
            val straddling = pages[6]
            assertEquals(60, straddling.items.count { !it.locked })
            assertEquals(30, straddling.items.count { it.locked })
            assertEquals(List(60) { repository.idAt(540 + it).toString() }, straddling.items.take(60).map { it.userId })
            assertTrue(pages.take(6).all { it.lockedFromIndex == null && it.unlockTier == null })
            pages.drop(6).forEach { page ->
                assertEquals(600, page.lockedFromIndex)
                assertEquals(SubscriptionTier.SELECT, page.unlockTier)
            }
            assertTrue(pages.last().resultLimitReached)
            assertNull(pages.last().nextCursor)
        }

    @Test
    fun selectViewerNeverSeesALockedCard() =
        runBlocking {
            val repository = SequentialGridRepository(totalCards = 1_500)
            val service = GridService(repository, subscriptionService = tierService(SubscriptionTier.SELECT))

            val pages = service.pageThrough(GridQueryRequestDto(limit = 120, acceptLockedCards = true))
            val cards = pages.flatMap { it.items }

            assertEquals(1_500, cards.size)
            assertTrue(cards.none { it.locked })
            assertTrue(pages.all { it.lockedFromIndex == null && it.unlockTier == null && !it.resultLimitReached })
        }

    @Test
    fun oldClientWithoutTheOptInStopsAtTheDepthAsBefore() =
        runBlocking {
            val repository = SequentialGridRepository(totalCards = 1_000)
            val service = GridService(repository, subscriptionService = tierService(SubscriptionTier.FREE))

            val pages = service.pageThrough(GridQueryRequestDto(limit = 25))
            val cards = pages.flatMap { it.items }

            assertEquals(listOf(25, 25, 10), pages.map { it.items.size })
            assertTrue(cards.none { it.locked })
            assertEquals(List(60) { repository.idAt(it).toString() }, cards.map { it.userId })
            assertTrue(pages.last().resultLimitReached)
            assertEquals(SubscriptionTier.FREE, pages.last().resultLimitTier)
            assertNull(pages.last().nextCursor)
            assertTrue(pages.all { it.lockedFromIndex == null && it.unlockTier == null })
        }

    @Test
    fun anOptInCursorDoesNotContinueARequestWithoutTheOptIn() =
        runBlocking {
            val repository = SequentialGridRepository(totalCards = 1_000)
            val service = GridService(repository, subscriptionService = tierService(SubscriptionTier.FREE))

            val first = service.queryGrid(viewer, GridQueryRequestDto(limit = 60, acceptLockedCards = true))
            val cursor = assertNotNull(first.nextCursor)

            val error =
                assertFailsWith<GridException> {
                    service.queryGrid(viewer, GridQueryRequestDto(limit = 60, cursor = cursor))
                }
            assertEquals("INVALID_GRID_CURSOR", error.code)
        }

    @Test
    fun aClientCannotUnlockByChangingTheRequestedLimit() =
        runBlocking {
            val repository = SequentialGridRepository(totalCards = 1_000)
            val service = GridService(repository, subscriptionService = tierService(SubscriptionTier.FREE))

            // A Free page is capped at 60 cards whatever is asked, so the cap and the lock line up.
            val first = service.queryGrid(viewer, GridQueryRequestDto(limit = 120, acceptLockedCards = true))
            assertEquals(60, first.items.size)
            assertTrue(first.items.none { it.locked })
            val second = service.queryGrid(viewer, GridQueryRequestDto(limit = 120, acceptLockedCards = true, cursor = first.nextCursor))
            assertEquals(60, second.items.size)
            assertTrue(second.items.all { it.locked })
        }

    @Test
    fun anEntitlementChangeMidScrollIsRespectedOnTheNextPage() =
        runBlocking {
            val repository = SequentialGridRepository(totalCards = 1_500)
            val subscriptions = mutableMapOf<UUID, SubscriptionRecord>()
            val service = SubscriptionService(StaticSubscriptionRepository(own = subscriptions), entitlementCacheTtl = Duration.ZERO)
            val grid = GridService(repository, subscriptionService = service)
            val request = GridQueryRequestDto(limit = 60, acceptLockedCards = true)

            val first = grid.queryGrid(viewer, request)
            val second = grid.queryGrid(viewer, request.copy(cursor = first.nextCursor))
            assertTrue(first.items.none { it.locked })
            assertTrue(second.items.all { it.locked })

            // Entitlement is read on every request (no cache here, as after a purchase invalidates it).
            // Upgrade to Social: the next page (positions 120-179) is unlocked, under the same cursor.
            subscriptions[viewer] = subscription(SubscriptionTier.SOCIAL)
            val third = grid.queryGrid(viewer, request.copy(cursor = second.nextCursor))
            assertTrue(third.items.none { it.locked })
            assertEquals(List(60) { repository.idAt(120 + it).toString() }, third.items.map { it.userId })
            assertNull(third.lockedFromIndex)

            // Downgrade back to Free: the next page is locked again.
            subscriptions.remove(viewer)
            val fourth = grid.queryGrid(viewer, request.copy(cursor = third.nextCursor))
            assertTrue(fourth.items.all { it.locked })
            assertEquals(60, fourth.lockedFromIndex)
            assertEquals(SubscriptionTier.SOCIAL, fourth.unlockTier)
        }

    @Test
    fun aDowngradePastTheLockedWindowEndsTheResults() =
        runBlocking {
            val repository = SequentialGridRepository(totalCards = 1_500)
            val subscriptions = mutableMapOf(viewer to subscription(SubscriptionTier.SOCIAL))
            val service = SubscriptionService(StaticSubscriptionRepository(own = subscriptions), entitlementCacheTtl = Duration.ZERO)
            val grid = GridService(repository, subscriptionService = service)
            val request = GridQueryRequestDto(limit = 60, acceptLockedCards = true)

            var page = grid.queryGrid(viewer, request)
            repeat(10) { page = grid.queryGrid(viewer, request.copy(cursor = assertNotNull(page.nextCursor))) }
            // 660 seen as Social. Now Free, whose locked window ends at 600: nothing more is served.
            subscriptions.remove(viewer)
            val limitsBefore = repository.receivedLimits.size
            val after = grid.queryGrid(viewer, request.copy(cursor = page.nextCursor))

            assertEquals(emptyList(), after.items)
            assertNull(after.nextCursor)
            assertTrue(after.resultLimitReached)
            assertEquals(60, after.lockedFromIndex)
            assertEquals(limitsBefore, repository.receivedLimits.size, "no ranking is spent past the window")
        }

    @Test
    fun aWidenedContinuationCrossesIntoTheLockedZone() =
        runBlocking {
            // Five people inside 25 km, so the first page widens to 100 km, where 300 more wait.
            val repository = SequentialGridRepository(totalCards = 5, minMatchRadiusMeters = 100_000, extraAtMaxRadius = 300)
            val service = GridService(repository, subscriptionService = tierService(SubscriptionTier.FREE))
            val request = GridQueryRequestDto(radiusMeters = 25_000, limit = 60, acceptLockedCards = true)

            val first = service.queryGrid(viewer, request)
            assertTrue(first.widened)
            assertTrue(first.items.none { it.locked })
            val second = service.queryGrid(viewer, request.copy(cursor = assertNotNull(first.nextCursor)))

            assertTrue(second.widened)
            assertEquals(60, second.items.size)
            assertTrue(second.items.all { it.locked })
            assertEquals(60, second.lockedFromIndex)
            assertEquals(listOf(25_000, 100_000, 100_000), repository.receivedRadii)
        }

    @Test
    fun noSubscriptionServiceMeansUnrestrictedAndNothingLocked() =
        runBlocking {
            val repository = SequentialGridRepository(totalCards = 300)
            val service = GridService(repository)

            val pages = service.pageThrough(GridQueryRequestDto(limit = 120, acceptLockedCards = true))

            assertEquals(300, pages.sumOf { it.items.size })
            assertTrue(pages.flatMap { it.items }.none { it.locked })
        }

    private suspend fun GridService.pageThrough(request: GridQueryRequestDto): List<GridQueryResponseDto> {
        val pages = mutableListOf(queryGrid(viewer, request))
        while (pages.last().nextCursor != null) {
            check(pages.size < 200) { "paging never ended" }
            pages += queryGrid(viewer, request.copy(cursor = pages.last().nextCursor))
        }
        return pages
    }

    private fun assertLockedCardsCarryNoIdentity(
        locked: List<GridCardDto>,
        repository: SequentialGridRepository,
    ) {
        val realIds = repository.allIds.map { it.toString() }.toSet()
        locked.forEach { card ->
            assertTrue(card.userId.startsWith("locked_"))
            assertFalse(card.userId in realIds)
            assertEquals(card.userId, card.rankToken)
            assertEquals("", card.displayName)
            assertNull(card.linkedPartner)
            assertNull(card.activeAgo)
        }
        assertEquals(locked.size, locked.map { it.userId }.toSet().size, "tokens are distinct per member")
    }

    private fun tierService(tier: String): SubscriptionService =
        SubscriptionService(
            StaticSubscriptionRepository(
                own = if (tier == SubscriptionTier.FREE) emptyMap() else mapOf(viewer to subscription(tier)),
            ),
        )

    private fun subscription(tier: String): SubscriptionRecord =
        SubscriptionRecord(
            subscriptionId = UUID.randomUUID(),
            userId = viewer,
            tier = tier,
            status = "active",
            currentPeriodStart = Instant.now().minusSeconds(86_400),
            currentPeriodEnd = Instant.now().plusSeconds(30 * 86_400L),
        )
}

/**
 * [totalCards] people in range below the max radius (none below [minMatchRadiusMeters]); the max
 * radius adds [extraAtMaxRadius] farther people after them, as a widened rank would.
 */
private class SequentialGridRepository(
    private val totalCards: Int,
    private val minMatchRadiusMeters: Int = 0,
    extraAtMaxRadius: Int = 0,
) : GridRepository {
    val receivedLimits = mutableListOf<Int>()
    val receivedRadii = mutableListOf<Int>()

    val allIds: List<UUID> = List(totalCards + extraAtMaxRadius) { idAt(it) }

    private val allCards =
        allIds.mapIndexed { index, id ->
            GridCardRecord(
                userId = id,
                displayName = "Locked paging $index",
                showDistance = true,
                distanceMeters = index.toDouble(),
                orderBucket = index,
            )
        }

    fun idAt(index: Int): UUID = UUID.nameUUIDFromBytes("grid-locked-paging-$index".toByteArray())

    override suspend fun queryNearby(
        viewerUserId: UUID,
        radiusMeters: Int,
        limit: Int,
        filters: DiscoveryFilters,
        cursor: GridCursorAnchor?,
        hiddenFromViewerIds: Set<UUID>,
    ): GridQueryResult {
        receivedLimits += limit
        receivedRadii += radiusMeters
        val cards =
            when {
                radiusMeters < minMatchRadiusMeters -> emptyList()
                radiusMeters >= 100_000 -> allCards
                else -> allCards.take(totalCards)
            }
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
