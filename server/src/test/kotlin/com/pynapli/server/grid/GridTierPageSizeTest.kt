package com.pynapli.server.grid

import com.pynapli.server.discovery.DiscoveryFilters
import com.pynapli.server.subscriptions.SubscriptionRecord
import com.pynapli.server.subscriptions.SubscriptionService
import com.pynapli.server.testsupport.StaticSubscriptionRepository
import com.pynapli.shared.network.dto.GridQueryRequestDto
import com.pynapli.shared.network.dto.SubscriptionTier
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * G2-2 ticket 4: the app asks for the largest page and the server serves the viewer's plan page size
 * (Free 60, Social 90, Select 120), reporting it as `pageSize`. A plan change between two pages keeps
 * the cursor valid, Section 3 locking stays positional across page sizes, and old clients that send
 * 60 page exactly as before.
 */
class GridTierPageSizeTest {
    private val viewer = UUID.fromString("00000000-0000-0000-0000-00000000f0a1")

    @Test
    fun eachPlanGetsItsPageSizeWhenTheAppAsksForTheMost() =
        runBlocking {
            val repository = NumberedGridRepository(totalCards = 1_000)
            val expected = mapOf(SubscriptionTier.FREE to 60, SubscriptionTier.SOCIAL to 90, SubscriptionTier.SELECT to 120)

            expected.forEach { (tier, size) ->
                val page = service(repository, tier).queryGrid(viewer, GridQueryRequestDto(limit = 120, acceptLockedCards = true))

                assertEquals(size, page.items.size, tier)
                assertEquals(size, page.pageSize, tier)
            }
        }

    @Test
    fun anOldClientAskingForSixtyStillGetsSixtyOnEveryPlan() =
        runBlocking {
            val repository = NumberedGridRepository(totalCards = 1_000)

            listOf(SubscriptionTier.FREE, SubscriptionTier.SOCIAL, SubscriptionTier.SELECT).forEach { tier ->
                val page = service(repository, tier).queryGrid(viewer, GridQueryRequestDto(limit = 60))
                assertEquals(60, page.items.size, tier)
                assertEquals(60, page.pageSize, tier)
            }
        }

    @Test
    fun anUpgradeBetweenTwoPagesContinuesTheSameListWithoutGapsOrRepeats() =
        runBlocking {
            val repository = NumberedGridRepository(totalCards = 1_000)
            val request = GridQueryRequestDto(limit = 120, acceptLockedCards = true)

            val socialPage = service(repository, SubscriptionTier.SOCIAL).queryGrid(viewer, request)
            val selectPage = service(repository, SubscriptionTier.SELECT).queryGrid(viewer, request.copy(cursor = socialPage.nextCursor))

            assertEquals(90, socialPage.items.size)
            assertEquals(120, selectPage.items.size)
            assertEquals(List(210) { repository.idAt(it).toString() }, (socialPage.items + selectPage.items).map { it.userId })
            // The next cursor is sealed under the new plan's size and keeps paging there.
            val third = service(repository, SubscriptionTier.SELECT).queryGrid(viewer, request.copy(cursor = selectPage.nextCursor))
            assertEquals(repository.idAt(210).toString(), third.items.first().userId)
        }

    @Test
    fun aLapseMidPagingLocksFromTheSamePositionWhateverThePageSize() =
        runBlocking {
            val repository = NumberedGridRepository(totalCards = 1_000)
            val request = GridQueryRequestDto(limit = 120, acceptLockedCards = true)

            // A Select page of 120, then the plan lapses to Free: locking starts at overall position 60.
            val selectPage = service(repository, SubscriptionTier.SELECT).queryGrid(viewer, request)
            val freePage = service(repository, SubscriptionTier.FREE).queryGrid(viewer, request.copy(cursor = selectPage.nextCursor))

            assertEquals(60, freePage.items.size)
            assertTrue(freePage.items.all { it.locked }, "everything past the Free depth is locked")
            assertEquals(60, freePage.lockedFromIndex)
        }

    @Test
    fun socialPagesOfNinetyStillStraddleTheLockBoundaryAtSixHundred() =
        runBlocking {
            val repository = NumberedGridRepository(totalCards = 1_500)
            val service = service(repository, SubscriptionTier.SOCIAL)
            val pages = mutableListOf(service.queryGrid(viewer, GridQueryRequestDto(limit = 120, acceptLockedCards = true)))
            while (pages.last().nextCursor != null) {
                check(pages.size < 50)
                pages += service.queryGrid(viewer, GridQueryRequestDto(limit = 120, acceptLockedCards = true, cursor = pages.last().nextCursor))
            }
            val cards = pages.flatMap { it.items }

            assertEquals(1_200, cards.size)
            assertTrue(cards.take(600).none { it.locked })
            assertTrue(cards.drop(600).all { it.locked })
            assertEquals(60, pages[6].items.count { !it.locked })
            assertEquals(30, pages[6].items.count { it.locked })
        }

    @Test
    fun aForgedOrForeignCursorIsStillRefused() =
        runBlocking {
            val repository = NumberedGridRepository(totalCards = 1_000)
            val other = UUID.fromString("00000000-0000-0000-0000-00000000f0a2")
            val foreign = service(repository, SubscriptionTier.SELECT).queryGrid(other, GridQueryRequestDto(limit = 120, acceptLockedCards = true)).nextCursor

            val error =
                assertFailsWith<GridException> {
                    service(repository, SubscriptionTier.SELECT).queryGrid(viewer, GridQueryRequestDto(limit = 120, acceptLockedCards = true, cursor = assertNotNull(foreign)))
                }
            assertEquals("INVALID_GRID_CURSOR", error.code)
        }

    private fun service(
        repository: GridRepository,
        tier: String,
    ): GridService =
        GridService(
            repository,
            subscriptionService =
                SubscriptionService(
                    StaticSubscriptionRepository(
                        own =
                            if (tier == SubscriptionTier.FREE) {
                                emptyMap()
                            } else {
                                mapOf(
                                    viewer to
                                        SubscriptionRecord(
                                            subscriptionId = UUID.randomUUID(),
                                            userId = viewer,
                                            tier = tier,
                                            status = "active",
                                            currentPeriodStart = Instant.now().minusSeconds(86_400),
                                            currentPeriodEnd = Instant.now().plusSeconds(30 * 86_400L),
                                        ),
                                )
                            },
                    ),
                ),
        )
}

/** Card n sits at order bucket n; the cursor anchor is the last card's bucket. */
private class NumberedGridRepository(
    totalCards: Int,
) : GridRepository {
    private val cards =
        List(totalCards) { index ->
            GridCardRecord(
                userId = idAt(index),
                displayName = "Numbered $index",
                showDistance = true,
                distanceMeters = index.toDouble(),
                orderBucket = index,
            )
        }

    fun idAt(index: Int): UUID = UUID.nameUUIDFromBytes("grid-tier-page-$index".toByteArray())

    override suspend fun queryNearby(
        viewerUserId: UUID,
        radiusMeters: Int,
        limit: Int,
        filters: DiscoveryFilters,
        cursor: GridCursorAnchor?,
        hiddenFromViewerIds: Set<UUID>,
    ): GridQueryResult {
        val start = cursor?.orderBucket?.plus(1) ?: 0
        val page = cards.drop(start).take(limit)
        return GridQueryResult(
            viewerHadLocation = true,
            cards = page,
            nextCursorAnchor = if (cards.size > start + limit && page.isNotEmpty()) page.last().toCursorAnchor() else null,
        )
    }
}
