package com.pynapli.server.grid

import com.pynapli.server.discovery.DiscoveryFilters
import com.pynapli.server.safety.JdbcBlockRepository
import com.pynapli.server.safety.JdbcHiddenProfileRepository
import com.pynapli.server.testsupport.PynapliTestDatabase
import com.pynapli.shared.network.dto.GridQueryRequestDto
import io.kotest.core.spec.style.StringSpec
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Paging through the shared cached rank window must give exactly the uncached walk: same ids, same
 * order, nobody skipped, no false end and no trailing empty page. The window is exclusion-free, so
 * it may hold the viewer (removed per viewer) and blocked or hidden people (removed in GridService);
 * when the window's remaining rows fill a page exactly, only a window that came back short can say
 * nothing follows, otherwise the page must be ranked for the viewer directly.
 */
class GridRankCacheWindowPagingTest :
    StringSpec({
        val dataSource = PynapliTestDatabase.dataSource

        beforeTest {
            PynapliTestDatabase.reset()
        }

        fun id(n: Int): UUID = UUID.fromString("00000000-0000-0000-0000-00000000e%03d".format(n))

        val radius = 25_000
        val viewer = id(0)

        // The viewer sits at distance 0 (order bucket 0); everyone else is 2+ km north and further
        // apart one by one, all inside the radius. Boosting everyone else pushes the viewer last.
        fun seed(
            others: Int,
            boostOthers: Boolean = false,
        ): List<UUID> {
            dataSource.insertDiscoverableUser(viewer, "Viewer", age = 30, longitude = -122.08, latitude = 37.42)
            return (1..others).map { n ->
                val other = id(n)
                dataSource.insertDiscoverableUser(other, "P$n", age = 30, longitude = -122.08, latitude = 37.44 + n * 0.005)
                if (boostOthers) dataSource.insertBoostActivation(other)
                other
            }
        }

        // The exclusion-free window exactly as the cached path loads it, to prove where the viewer is.
        fun exclusionFreeWindow(window: Int): List<UUID> =
            dataSource.connection.use { connection ->
                val key = assertNotNull(connection.findGridViewerLocationCacheKey(viewer))
                connection
                    .queryGridRank(
                        viewerUserId = viewer,
                        radiusMeters = radius,
                        limit = window,
                        filters = DiscoveryFilters(),
                        userSpecificViewerId = null,
                        orderSeed = key.orderSeed,
                    ).map { it.userId }
            }

        suspend fun JdbcGridRepository.walkPages(pageSize: Int): List<List<UUID>> {
            val pages = mutableListOf<List<UUID>>()
            var cursor: GridCursorAnchor? = null
            do {
                val page = queryNearby(viewer, radius, pageSize, DiscoveryFilters(), cursor)
                pages += page.cards.map { it.userId }
                cursor = page.nextCursorAnchor
            } while (cursor != null && pages.size < 50)
            return pages
        }

        fun uncachedRepository() = JdbcGridRepository(dataSource, rankCacheTtl = Duration.ZERO)

        fun cachedRepository(window: Int) =
            JdbcGridRepository(dataSource, rankCacheTtl = Duration.ofMinutes(5), rankCacheWindow = window)

        fun assertWellFormed(
            pages: List<List<UUID>>,
            pageSize: Int,
            everyone: List<UUID>,
            label: String,
        ) {
            val ids = pages.flatten()
            assertEquals(ids.distinct(), ids, "$label: duplicate card")
            assertEquals(everyone.toSet(), ids.toSet(), "$label: walk did not reach everyone in range")
            assertTrue(pages.last().isNotEmpty(), "$label: trailing empty page")
            pages.dropLast(1).forEach { assertEquals(pageSize, it.size, "$label: short non-final page") }
        }

        "a cached window whose remaining rows exactly fill a page does not end the walk early" {
            runBlocking {
                val everyone = seed(others = 10)
                // Window of 4 = the viewer + 3 others: pages of 3 fill exactly from the window.
                assertEquals(viewer, exclusionFreeWindow(4).first(), "the viewer must rank first in the shared window")

                val want = uncachedRepository().walkPages(pageSize = 3)
                assertWellFormed(want, 3, everyone, "uncached")

                val got = cachedRepository(window = 4).walkPages(pageSize = 3)
                assertEquals(want.flatten(), got.flatten(), "cached window of 4, pages of 3")
                assertWellFormed(got, 3, everyone, "cached window of 4, pages of 3")
            }
        }

        "cached paging matches uncached paging for even and uneven page sizes, viewer inside the window" {
            runBlocking {
                val everyone = seed(others = 10)
                listOf(4, 5, 6).forEach { window ->
                    assertTrue(viewer in exclusionFreeWindow(window), "viewer must be inside the window of $window")
                    listOf(2, 3, 4, 5).forEach { pageSize ->
                        val label = "window $window, pages of $pageSize, viewer inside"
                        val want = uncachedRepository().walkPages(pageSize)
                        val got = cachedRepository(window).walkPages(pageSize)
                        assertEquals(want.flatten(), got.flatten(), label)
                        assertWellFormed(got, pageSize, everyone, label)
                    }
                }
            }
        }

        "cached paging matches uncached paging for even and uneven page sizes, viewer outside the window" {
            runBlocking {
                val everyone = seed(others = 10, boostOthers = true)
                listOf(4, 5, 6).forEach { window ->
                    assertFalse(viewer in exclusionFreeWindow(window), "viewer must be outside the window of $window")
                    // Pages of 2 (and 3 in the window of 6) divide the window evenly: the window's last
                    // rows fill a page exactly and nothing of the window is left to say more follow.
                    listOf(2, 3, 4, 5).forEach { pageSize ->
                        val label = "window $window, pages of $pageSize, viewer outside"
                        val want = uncachedRepository().walkPages(pageSize)
                        val got = cachedRepository(window).walkPages(pageSize)
                        assertEquals(want.flatten(), got.flatten(), label)
                        assertWellFormed(got, pageSize, everyone, label)
                    }
                }
            }
        }

        "a window that holds everyone in range ends at the true end with no extra empty page" {
            runBlocking {
                val everyone = seed(others = 5)
                // 6 people in range (viewer included); a window of 8 comes back short, so it holds
                // everyone and the cached path can answer the last page itself.
                assertEquals(6, exclusionFreeWindow(8).size)
                listOf(8, 6).forEach { window ->
                    listOf(5, 3, 2).forEach { pageSize ->
                        val label = "window $window, pages of $pageSize"
                        val want = uncachedRepository().walkPages(pageSize)
                        val got = cachedRepository(window).walkPages(pageSize)
                        assertEquals(want, got, label)
                        assertWellFormed(got, pageSize, everyone, label)
                        assertEquals((everyone.size + pageSize - 1) / pageSize, got.size, "$label: page count")
                    }
                }
                // One page exactly: the cached window answers "no more" on its own.
                val only = cachedRepository(8).queryNearby(viewer, radius, 5)
                assertEquals(everyone.toSet(), only.cards.map { it.userId }.toSet())
                assertNull(only.nextCursorAnchor, "a window holding everyone must not offer another page")
            }
        }

        "blocked and hidden people inside the cached window do not shorten the walk through GridService" {
            runBlocking {
                val everyone = seed(others = 30)
                val blocked = everyone[1]
                val hidden = everyone[3]
                dataSource.insertBlock(viewer, blocked)
                dataSource.insertHiddenProfile(viewer, hidden)
                val shown = everyone - blocked - hidden

                suspend fun GridService.walk(): List<String> {
                    val seen = mutableListOf<String>()
                    var cursor: String? = null
                    var pages = 0
                    do {
                        val request = GridQueryRequestDto(radiusMeters = radius, limit = GridService.MIN_PAGE_SIZE, cursor = cursor)
                        val page = queryGrid(viewer, request)
                        seen += page.items.map { it.userId }
                        cursor = page.nextCursor
                        pages++
                    } while (cursor != null && pages < 20)
                    return seen
                }

                fun service(repository: JdbcGridRepository) =
                    GridService(
                        repository = repository,
                        blockRepository = JdbcBlockRepository(dataSource),
                        hiddenProfileRepository = JdbcHiddenProfileRepository(dataSource),
                    )

                val want = service(uncachedRepository()).walk()
                assertEquals(want.distinct(), want, "uncached: duplicate card")
                assertEquals(shown.map { it.toString() }.toSet(), want.toSet(), "uncached: walk did not reach everyone shown")

                // 13 = viewer + 12 others (exact fill of the first page); 14 serves the first page,
                // blocked and hidden rows included, from the window; 25 fills the second page exactly.
                listOf(13, 14, 25).forEach { window ->
                    val inWindow = exclusionFreeWindow(window)
                    assertTrue(
                        viewer in inWindow && blocked in inWindow && hidden in inWindow,
                        "window $window must hold viewer, blocked and hidden",
                    )
                    assertEquals(want, service(cachedRepository(window)).walk(), "GridService walk, cached window of $window")
                }
            }
        }
    })
