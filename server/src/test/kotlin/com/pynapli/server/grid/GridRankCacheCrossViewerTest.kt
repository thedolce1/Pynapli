package com.pynapli.server.grid

import com.pynapli.server.testsupport.PynapliTestDatabase
import io.kotest.core.spec.style.StringSpec
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * AUDIT-14: the Grid rank cache is keyed by the viewer's location bucket, but the cached window
 * used to be ranked from the exact GPS of whichever viewer filled it. A second viewer in the same
 * bucket then saw the filler's distances, and once paging ran past the window onto the uncached
 * path (ranked from the second viewer's own GPS) the keyset anchor came from a different origin,
 * so cards were duplicated or skipped.
 */
class GridRankCacheCrossViewerTest :
    StringSpec({
        val dataSource = PynapliTestDatabase.dataSource

        beforeTest {
            PynapliTestDatabase.reset()
        }

        "two viewers in one cache cell see their own distances and page with no skips or duplicates" {
            runBlocking {
                val viewerA = UUID.fromString("00000000-0000-0000-0000-000000014001")
                val viewerB = UUID.fromString("00000000-0000-0000-0000-000000014002")
                val west = UUID.fromString("00000000-0000-0000-0000-000000014003")
                val east = UUID.fromString("00000000-0000-0000-0000-000000014004")
                // A and B ~35 m apart, same cache cell. West is ~500 m west of A, East ~520 m east of A:
                // from A, West comes first; from B (35 m further east), East comes first.
                dataSource.insertDiscoverableUser(viewerA, "A", age = 30, longitude = -122.08405, latitude = 37.4220)
                dataSource.insertDiscoverableUser(viewerB, "B", age = 30, longitude = -122.08445, latitude = 37.4220)
                dataSource.insertDiscoverableUser(west, "West", age = 30, longitude = -122.08970, latitude = 37.4220)
                dataSource.insertDiscoverableUser(east, "East", age = 30, longitude = -122.07817, latitude = 37.4220)

                val shared = JdbcGridRepository(dataSource, rankCacheTtl = Duration.ofMinutes(5), rankCacheWindow = 4)
                val uncached = JdbcGridRepository(dataSource, rankCacheTtl = Duration.ZERO)

                // A fills the shared window.
                shared.queryNearby(viewerA, radiusMeters = 100_000, limit = 2)

                // B pages through using the shared repository.
                val seen = mutableListOf<UUID>()
                val sharedDistances = mutableMapOf<UUID, Double>()
                var cursor: GridCursorAnchor? = null
                var pages = 0
                do {
                    val page = shared.queryNearby(viewerB, radiusMeters = 100_000, limit = 2, cursor = cursor)
                    page.cards.forEach {
                        seen += it.userId
                        sharedDistances[it.userId] = it.distanceMeters
                    }
                    cursor = page.nextCursorAnchor
                    pages++
                } while (cursor != null && pages < 10)

                assertEquals(seen.distinct(), seen, "B must never see a card twice: $seen")
                assertEquals(setOf(viewerA, west, east), seen.toSet(), "B must see every candidate exactly once")

                // B's distances must be what B's own (uncached) query computes, not the filler's.
                val own = uncached.queryNearby(viewerB, radiusMeters = 100_000, limit = 10)
                val ownDistances = own.cards.associate { it.userId to it.distanceMeters }
                sharedDistances.forEach { (id, d) ->
                    assertEquals(ownDistances.getValue(id), d, 0.5, "Distance to $id served from the shared cache must be B's own")
                }
                assertTrue(sharedDistances.isNotEmpty())
            }
        }

        "viewers sharing a cache key always share the location cell the window was ranked from" {
            runBlocking {
                // ~22 m apart and in the same old E3 (~111 m) cache bucket, but on either side of a
                // 0.01-degree location-cell boundary, so their coarse distances genuinely differ.
                val viewerA = UUID.fromString("00000000-0000-0000-0000-000000014011")
                val viewerB = UUID.fromString("00000000-0000-0000-0000-000000014012")
                val target = UUID.fromString("00000000-0000-0000-0000-000000014013")
                dataSource.insertDiscoverableUser(viewerA, "A", age = 30, longitude = -122.08, latitude = 37.42490)
                dataSource.insertDiscoverableUser(viewerB, "B", age = 30, longitude = -122.08, latitude = 37.42510)
                dataSource.insertDiscoverableUser(target, "T", age = 30, longitude = -122.08, latitude = 37.46)

                val shared = JdbcGridRepository(dataSource, rankCacheTtl = Duration.ofMinutes(5))
                val uncached = JdbcGridRepository(dataSource, rankCacheTtl = Duration.ZERO)

                shared.queryNearby(viewerA, radiusMeters = 100_000, limit = 12)
                val viaCache = shared.queryNearby(viewerB, radiusMeters = 100_000, limit = 12).cards.single { it.userId == target }
                val own = uncached.queryNearby(viewerB, radiusMeters = 100_000, limit = 12).cards.single { it.userId == target }

                assertEquals(own.distanceMeters, viaCache.distanceMeters, 0.5, "B must not be served distances ranked from A's cell")
            }
        }
    })
