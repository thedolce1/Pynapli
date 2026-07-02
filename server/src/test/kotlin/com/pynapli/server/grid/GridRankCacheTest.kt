package com.pynapli.server.grid

import com.pynapli.server.dependencies.RedisCache
import com.pynapli.server.discovery.DiscoveryFilters
import com.pynapli.server.safety.JdbcBlockRepository
import com.pynapli.server.safety.JdbcHiddenProfileRepository
import com.pynapli.server.swipe.JdbcSwipeRepository
import com.pynapli.server.swipe.SwipeException
import com.pynapli.server.swipe.SwipeService
import com.pynapli.server.swipe.canonicalPair
import com.pynapli.server.testsupport.PynapliTestDatabase
import com.pynapli.shared.network.dto.GridQueryRequestDto
import com.pynapli.shared.network.dto.SwipeAction
import com.pynapli.shared.network.dto.SwipeActionRequestDto
import com.pynapli.shared.network.dto.SwipeDeckRequestDto
import io.kotest.core.spec.style.StringSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GridRankCacheTest :
    StringSpec({
        val dataSource = PynapliTestDatabase.dataSource

        fun redisCache(): RedisCache = PynapliTestDatabase.redisCache()

        beforeTest {
            PynapliTestDatabase.reset()
            PynapliTestDatabase.flushRedis()
        }

        "grid service filters blocked users out of the cached candidate window" {
            // Safety: the cached candidate window is shared cross-viewer, so it is
            // intentionally EXCLUSION-FREE (no per-viewer block baked in). GridService
            // applies the per-viewer block filter post-repository, so a blocked user
            // can never reach the client even when present in the cached window.
            runBlocking {
                val viewer = UUID.fromString("00000000-0000-0000-0000-000000000961")
                val blockedTarget = UUID.fromString("00000000-0000-0000-0000-000000000962")
                val visibleTarget = UUID.fromString("00000000-0000-0000-0000-000000000963")
                dataSource.insertDiscoverableUser(viewer, "Viewer", age = 38, longitude = -122.0841, latitude = 37.4220)
                dataSource.insertDiscoverableUser(blockedTarget, "Blocked", age = 35, longitude = -122.0836, latitude = 37.4226)
                dataSource.insertDiscoverableUser(visibleTarget, "Visible", age = 36, longitude = -122.0840, latitude = 37.4230)
                dataSource.insertBlock(viewer, blockedTarget)

                val service =
                    GridService(
                        repository = JdbcGridRepository(dataSource),
                        blockRepository = JdbcBlockRepository(dataSource),
                        hiddenProfileRepository = JdbcHiddenProfileRepository(dataSource),
                    )
                val response =
                    service.queryGrid(
                        viewer,
                        GridQueryRequestDto(radiusMeters = 100_000, limit = 10),
                    )

                assertEquals(false, response.items.any { it.userId == blockedTarget.toString() }, "Blocked user must never reach the client")
                assertTrue(response.items.any { it.userId == visibleTarget.toString() })
            }
        }

        "grid rank cache serves a second identical query without re-running the rank SQL" {
            runBlocking {
                val viewer = UUID.fromString("00000000-0000-0000-0000-000000003001")
                val target = UUID.fromString("00000000-0000-0000-0000-000000003002")
                dataSource.insertDiscoverableUser(viewer, "Viewer", age = 38, longitude = -122.0841, latitude = 37.4220)
                dataSource.insertDiscoverableUser(target, "Maya", age = 35, longitude = -122.0836, latitude = 37.4226)
                val counting = RankCountingDataSource(dataSource)
                val repository = JdbcGridRepository(counting)

                val first = repository.queryNearby(viewer, radiusMeters = 100_000, limit = 10)
                val rankRunsAfterFirst = counting.rankRunCount()
                val second = repository.queryNearby(viewer, radiusMeters = 100_000, limit = 10)
                val rankRunsAfterSecond = counting.rankRunCount()

                assertEquals(listOf(target), first.cards.map { it.userId })
                assertEquals(listOf(target), second.cards.map { it.userId })
                assertEquals(1, rankRunsAfterFirst, "First query must run the rank SQL exactly once")
                assertEquals(
                    1,
                    rankRunsAfterSecond,
                    "Second identical query within TTL must be served from cache without re-running the rank SQL",
                )
            }
        }

        "grid rank cache window is shared across viewers in the same bucket and filter" {
            runBlocking {
                val viewerA = UUID.fromString("00000000-0000-0000-0000-000000003011")
                val viewerB = UUID.fromString("00000000-0000-0000-0000-000000003012")
                val target = UUID.fromString("00000000-0000-0000-0000-000000003013")
                // Both viewers at the same coarse (E3 ~111m) bucket center.
                dataSource.insertDiscoverableUser(viewerA, "A", age = 38, longitude = -122.0841, latitude = 37.4220)
                dataSource.insertDiscoverableUser(viewerB, "B", age = 39, longitude = -122.08409, latitude = 37.42201)
                dataSource.insertDiscoverableUser(target, "Maya", age = 35, longitude = -122.0836, latitude = 37.4226)
                val counting = RankCountingDataSource(dataSource)
                val repository = JdbcGridRepository(counting)

                val a = repository.queryNearby(viewerA, radiusMeters = 100_000, limit = 10)
                val b = repository.queryNearby(viewerB, radiusMeters = 100_000, limit = 10)

                // Each viewer sees everyone in the shared window except themselves.
                assertEquals(setOf(viewerB, target), a.cards.map { it.userId }.toSet())
                assertEquals(setOf(viewerA, target), b.cards.map { it.userId }.toSet())
                assertEquals(
                    1,
                    counting.rankRunCount(),
                    "Two viewers in the same bucket + filter must share one cached rank window",
                )
            }
        }

        "grid rank cache never returns the viewer themselves even when present in the shared window" {
            runBlocking {
                val viewerA = UUID.fromString("00000000-0000-0000-0000-000000003021")
                val viewerB = UUID.fromString("00000000-0000-0000-0000-000000003022")
                // Same bucket so viewerB's query reuses a window that contains viewerB.
                dataSource.insertDiscoverableUser(viewerA, "A", age = 38, longitude = -122.0841, latitude = 37.4220)
                dataSource.insertDiscoverableUser(viewerB, "B", age = 39, longitude = -122.08409, latitude = 37.42201)
                val repository = JdbcGridRepository(dataSource)

                // viewerA populates the shared window (window contains both A and B,
                // minus A's self row).
                val a = repository.queryNearby(viewerA, radiusMeters = 100_000, limit = 10)
                val b = repository.queryNearby(viewerB, radiusMeters = 100_000, limit = 10)

                assertTrue(a.cards.none { it.userId == viewerA }, "Viewer A must never see themselves")
                assertTrue(b.cards.none { it.userId == viewerB }, "Viewer B must never see themselves from the shared window")
                assertEquals(listOf(viewerA), b.cards.map { it.userId })
            }
        }

        "grid cursor pagination over the cached window matches the uncached ordering" {
            runBlocking {
                val viewer = UUID.fromString("00000000-0000-0000-0000-000000003031")
                val first = UUID.fromString("00000000-0000-0000-0000-000000003032")
                val second = UUID.fromString("00000000-0000-0000-0000-000000003033")
                val third = UUID.fromString("00000000-0000-0000-0000-000000003034")
                dataSource.insertDiscoverableUser(viewer, "Viewer", age = 38, longitude = -122.0841, latitude = 37.4220)
                dataSource.insertDiscoverableUser(first, "First", age = 35, longitude = -122.0836, latitude = 37.4226)
                dataSource.insertDiscoverableUser(second, "Second", age = 36, longitude = -122.0838, latitude = 37.4228)
                dataSource.insertDiscoverableUser(third, "Third", age = 37, longitude = -122.0840, latitude = 37.4230)

                // Uncached reference ordering (linkedOnly bypasses the cache but
                // returns the same candidate set since all three are non-linked and
                // we filter linkedOnly=false). Use a fresh repo with cache disabled.
                val cachedRepo = JdbcGridRepository(dataSource)
                val uncachedRepo = JdbcGridRepository(dataSource, rankCacheTtl = Duration.ZERO)

                val cachedFirst = cachedRepo.queryNearby(viewer, radiusMeters = 100_000, limit = 2)
                val cachedSecond =
                    cachedRepo.queryNearby(
                        viewer,
                        radiusMeters = 100_000,
                        limit = 2,
                        cursor = assertNotNull(cachedFirst.nextCursorAnchor),
                    )
                val uncachedFirst = uncachedRepo.queryNearby(viewer, radiusMeters = 100_000, limit = 2)
                val uncachedSecond =
                    uncachedRepo.queryNearby(
                        viewer,
                        radiusMeters = 100_000,
                        limit = 2,
                        cursor = assertNotNull(uncachedFirst.nextCursorAnchor),
                    )

                assertEquals(listOf(first, second), cachedFirst.cards.map { it.userId })
                assertEquals(listOf(third), cachedSecond.cards.map { it.userId })
                assertEquals(
                    uncachedFirst.cards.map { it.userId },
                    cachedFirst.cards.map { it.userId },
                    "Cached page 1 must match uncached ordering",
                )
                assertEquals(
                    uncachedSecond.cards.map { it.userId },
                    cachedSecond.cards.map { it.userId },
                    "Cached page 2 must match uncached ordering",
                )
                assertEquals(null, cachedSecond.nextCursorAnchor)
            }
        }

        "grid linkedOnly filter bypasses the cache and returns only linked profiles" {
            runBlocking {
                val viewer = UUID.fromString("00000000-0000-0000-0000-000000003041")
                val linkedTarget = UUID.fromString("00000000-0000-0000-0000-000000003042")
                val singleTarget = UUID.fromString("00000000-0000-0000-0000-000000003043")
                val partner = UUID.fromString("00000000-0000-0000-0000-000000003044")
                dataSource.insertDiscoverableUser(viewer, "Viewer", age = 38, longitude = -122.0841, latitude = 37.4220)
                dataSource.insertDiscoverableUser(linkedTarget, "Maya", age = 36, longitude = -122.0836, latitude = 37.4226)
                dataSource.insertDiscoverableUser(singleTarget, "Alex", age = 31, longitude = -122.0838, latitude = 37.4228)
                dataSource.insertDiscoverableUser(partner, "Noa", age = 35, longitude = -122.0851, latitude = 37.4235)
                dataSource.insertActiveProfileLink(userId = linkedTarget, linkedUserId = partner)
                val counting = RankCountingDataSource(dataSource)
                val repository = JdbcGridRepository(counting)

                val linkedOnly =
                    repository
                        .queryNearby(
                            viewer,
                            radiusMeters = 100_000,
                            limit = 10,
                            filters = DiscoveryFilters(linkedOnly = true),
                        ).cards
                // A second linkedOnly query must re-run the rank SQL (cache bypassed).
                repository.queryNearby(
                    viewer,
                    radiusMeters = 100_000,
                    limit = 10,
                    filters = DiscoveryFilters(linkedOnly = true),
                )

                assertEquals(listOf(linkedTarget), linkedOnly.map { it.userId })
                assertEquals(2, counting.rankRunCount(), "linkedOnly must bypass the shared cache on every call")
            }
        }

        "grid travel-active viewer bypasses the cache and follows the travel location" {
            runBlocking {
                val viewer = UUID.fromString("00000000-0000-0000-0000-000000003051")
                val sfLocal = UUID.fromString("00000000-0000-0000-0000-000000003052")
                val nycLocal = UUID.fromString("00000000-0000-0000-0000-000000003053")
                dataSource.insertDiscoverableUser(viewer, "Viewer", age = 38, longitude = -122.4194, latitude = 37.7749)
                dataSource.insertDiscoverableUser(sfLocal, "Sage", age = 32, longitude = -122.4180, latitude = 37.7740)
                dataSource.insertDiscoverableUser(nycLocal, "Maya", age = 34, longitude = -74.0060, latitude = 40.7128)
                dataSource.insertActiveTravelLocation(viewer, "New York", 40.7128, -74.0060)
                val counting = RankCountingDataSource(dataSource)
                val repository = JdbcGridRepository(counting)

                val first = repository.queryNearby(viewer, radiusMeters = 5_000, limit = 10)
                val second = repository.queryNearby(viewer, radiusMeters = 5_000, limit = 10)

                assertEquals(listOf(nycLocal), first.cards.map { it.userId })
                assertEquals(listOf(nycLocal), second.cards.map { it.userId })
                assertEquals(
                    2,
                    counting.rankRunCount(),
                    "Travel-active viewer must bypass the cross-viewer cache (bucket is viewer-specific)",
                )
            }
        }

        "grid cached results are byte-identical to the uncached query for a representative case" {
            runBlocking {
                val viewer = UUID.fromString("00000000-0000-0000-0000-000000003061")
                val target = UUID.fromString("00000000-0000-0000-0000-000000003062")
                val partner = UUID.fromString("00000000-0000-0000-0000-000000003063")
                val boosted = UUID.fromString("00000000-0000-0000-0000-000000003064")
                dataSource.insertDiscoverableUser(viewer, "Viewer", age = 38, longitude = -122.0841, latitude = 37.4220)
                dataSource.insertDiscoverableUser(target, "Maya", age = 35, longitude = -122.0836, latitude = 37.4226)
                dataSource.insertDiscoverableUser(partner, "Alex", age = 39, longitude = -122.0851, latitude = 37.4235)
                dataSource.insertDiscoverableUser(boosted, "Boosted", age = 36, longitude = -122.0900, latitude = 37.4280)
                dataSource.insertActiveProfileLink(userId = target, linkedUserId = partner)
                dataSource.insertBoostActivation(boosted)

                val cachedRepo = JdbcGridRepository(dataSource)
                val uncachedRepo = JdbcGridRepository(dataSource, rankCacheTtl = Duration.ZERO)

                val cached = cachedRepo.queryNearby(viewer, radiusMeters = 100_000, limit = 10)
                val uncached = uncachedRepo.queryNearby(viewer, radiusMeters = 100_000, limit = 10)

                assertEquals(uncached.cards, cached.cards, "Cached cards must be byte-identical to the uncached query")
                assertEquals(uncached.nextCursorAnchor, cached.nextCursorAnchor)
            }
        }

        "grid redis candidate cache shares the ranked window across separate repository instances" {
            // Headline of the Redis upgrade: two server instances (here, two repos
            // with independent in-process state and their own DB connections) share
            // ONE ranked window via Redis, so the expensive rank SQL runs once
            // fleet-wide for the same coarse bucket + filter — what an in-memory
            // per-instance cache cannot do.
            runBlocking {
                val viewer = UUID.fromString("00000000-0000-0000-0000-000000003071")
                val target = UUID.fromString("00000000-0000-0000-0000-000000003072")
                dataSource.insertDiscoverableUser(viewer, "Viewer", age = 38, longitude = -122.0841, latitude = 37.4220)
                dataSource.insertDiscoverableUser(target, "Maya", age = 35, longitude = -122.0836, latitude = 37.4226)

                val cache = redisCache()
                try {
                    val countingA = RankCountingDataSource(dataSource)
                    val countingB = RankCountingDataSource(dataSource)
                    val instanceA = JdbcGridRepository(countingA, candidateRankCache = RedisGridCandidateRankCache(cache, Duration.ofSeconds(30)))
                    val instanceB = JdbcGridRepository(countingB, candidateRankCache = RedisGridCandidateRankCache(cache, Duration.ofSeconds(30)))

                    val a = instanceA.queryNearby(viewer, radiusMeters = 100_000, limit = 10)
                    val b = instanceB.queryNearby(viewer, radiusMeters = 100_000, limit = 10)

                    assertEquals(listOf(target), a.cards.map { it.userId })
                    assertEquals(listOf(target), b.cards.map { it.userId })
                    assertEquals(1, countingA.rankRunCount(), "Instance A must populate the shared Redis window once")
                    assertEquals(
                        0,
                        countingB.rankRunCount(),
                        "Instance B must serve from the Redis window A populated, without re-running the rank SQL",
                    )
                } finally {
                    cache.close()
                }
            }
        }

        "grid redis candidate cache fails open to direct queries when redis is unavailable" {
            runBlocking {
                val viewer = UUID.fromString("00000000-0000-0000-0000-000000003081")
                val target = UUID.fromString("00000000-0000-0000-0000-000000003082")
                dataSource.insertDiscoverableUser(viewer, "Viewer", age = 38, longitude = -122.0841, latitude = 37.4220)
                dataSource.insertDiscoverableUser(target, "Maya", age = 35, longitude = -122.0836, latitude = 37.4226)

                val cache = redisCache()
                cache.close() // simulate a Redis outage: every command now throws.
                val repository = JdbcGridRepository(dataSource, candidateRankCache = RedisGridCandidateRankCache(cache, Duration.ofSeconds(30)))

                val result = repository.queryNearby(viewer, radiusMeters = 100_000, limit = 10)

                assertEquals(listOf(target), result.cards.map { it.userId }, "A Redis outage must degrade to direct queries, not break the Grid")
            }
        }

        "grid redis window codec round-trips ranked rows" {
            val rows =
                listOf(
                    GridRankRow(
                        userId = UUID.fromString("00000000-0000-0000-0000-0000000030a1"),
                        displayName = "Maya | with: delimiters \"and\" quotes",
                        showDistance = true,
                        boostBucket = 0,
                        distanceBucketMeters = 1234,
                        distanceMeters = 1234.56,
                        visiting = false,
                    ),
                    GridRankRow(
                        userId = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff"),
                        displayName = "Pynapli User",
                        showDistance = false,
                        boostBucket = 1,
                        distanceBucketMeters = 0,
                        distanceMeters = 0.0,
                        visiting = true,
                    ),
                )

            val decoded = GridRankWindowCodec.decode(GridRankWindowCodec.encode(rows))

            assertEquals(rows, decoded, "Codec must round-trip the ranked window exactly (incl. unsigned-high-bit UUID and delimiter-laden names)")
        }
    })

private class RankCountingDataSource(
    private val delegate: DataSource,
) : DataSource by delegate {
    private val rankRuns =
        java.util.concurrent.atomic
            .AtomicInteger(0)

    fun rankRunCount(): Int = rankRuns.get()

    override fun getConnection(): java.sql.Connection = RankCountingConnection(delegate.connection, rankRuns)

    override fun getConnection(
        username: String?,
        password: String?,
    ): java.sql.Connection = RankCountingConnection(delegate.getConnection(username, password), rankRuns)
}

private class RankCountingConnection(
    private val delegate: java.sql.Connection,
    private val rankRuns: java.util.concurrent.atomic.AtomicInteger,
) : java.sql.Connection by delegate {
    override fun prepareStatement(sql: String): java.sql.PreparedStatement {
        // The exclusion-free rank window query is the only one keyed off the
        // base_candidates CTE; counting it proves whether the rank SQL re-ran.
        if (sql.contains("base_candidates")) {
            rankRuns.incrementAndGet()
        }
        return delegate.prepareStatement(sql)
    }
}
