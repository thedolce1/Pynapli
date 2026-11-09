package com.pynapli.server.grid

import com.pynapli.server.discovery.DiscoveryFilters
import com.pynapli.server.testsupport.PynapliTestDatabase
import io.kotest.core.spec.style.StringSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GridPaginationAndScaleTest :
    StringSpec({
        val dataSource = PynapliTestDatabase.dataSource

        beforeTest {
            PynapliTestDatabase.reset()
        }

        "grid cursor pagination returns stable pages without overlap" {
            runBlocking {
                val viewer = UUID.fromString("00000000-0000-0000-0000-000000000951")
                val firstTarget = UUID.fromString("00000000-0000-0000-0000-000000000952")
                val secondTarget = UUID.fromString("00000000-0000-0000-0000-000000000953")
                val thirdTarget = UUID.fromString("00000000-0000-0000-0000-000000000954")
                dataSource.insertDiscoverableUser(viewer, "Viewer", age = 38, longitude = -122.0841, latitude = 37.4220)
                dataSource.insertDiscoverableUser(firstTarget, "First", age = 35, longitude = -122.0836, latitude = 37.4226)
                dataSource.insertDiscoverableUser(secondTarget, "Second", age = 36, longitude = -122.0838, latitude = 37.4228)
                dataSource.insertDiscoverableUser(thirdTarget, "Third", age = 37, longitude = -122.0840, latitude = 37.4230)

                val repository = JdbcGridRepository(dataSource)
                val firstPage =
                    repository.queryNearby(
                        viewerUserId = viewer,
                        radiusMeters = 100_000,
                        limit = 2,
                    )
                val secondPage =
                    repository.queryNearby(
                        viewerUserId = viewer,
                        radiusMeters = 100_000,
                        limit = 2,
                        cursor = firstPage.nextCursorAnchor,
                    )

                assertEquals(listOf(firstTarget, secondTarget), firstPage.cards.map { it.userId })
                assertEquals(GridCursorAnchor(1, firstPage.cards.last().distanceBucketMeters, secondTarget), firstPage.nextCursorAnchor)
                assertEquals(listOf(thirdTarget), secondPage.cards.map { it.userId })
                assertEquals(null, secondPage.nextCursorAnchor)
            }
        }

        "grid query handles 5000 nearby candidates with stable cursor pages" {
            runBlocking {
                val viewer = UUID.fromString("00000000-0000-0000-0000-000000000971")
                dataSource.insertDiscoverableUser(viewer, "Viewer", age = 38, longitude = -122.0841, latitude = 37.4220)
                dataSource.insertDenseDiscoverableUsers(
                    count = 5_000,
                    baseLongitude = -122.0841,
                    baseLatitude = 37.4220,
                )
                dataSource.analyzeGridPlannerTables()

                val repository = JdbcGridRepository(dataSource)
                val startedAt = System.nanoTime()
                val firstPage =
                    repository.queryNearby(
                        viewerUserId = viewer,
                        radiusMeters = 100_000,
                        limit = 120,
                    )
                val secondPage =
                    repository.queryNearby(
                        viewerUserId = viewer,
                        radiusMeters = 100_000,
                        limit = 120,
                        cursor = assertNotNull(firstPage.nextCursorAnchor),
                    )
                val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000
                println("Grid dense 5000 candidate two-page query completed in ${elapsedMillis}ms")

                assertEquals(120, firstPage.cards.size)
                assertEquals(120, secondPage.cards.size)
                assertTrue(
                    firstPage.cards
                        .map { it.userId }
                        .intersect(secondPage.cards.map { it.userId }.toSet())
                        .isEmpty(),
                )
                assertTrue(
                    elapsedMillis < 10_000,
                    "Dense grid query should remain comfortably under 10s in local Testcontainers; actual=${elapsedMillis}ms",
                )
            }
        }

        "grid query handles 100 concurrent filtered requests over 5000 nearby candidates" {
            runBlocking {
                val viewer = UUID.fromString("00000000-0000-0000-0000-000000000972")
                dataSource.insertDiscoverableUser(viewer, "Viewer", age = 38, longitude = -122.0841, latitude = 37.4220)
                dataSource.insertDenseDiscoverableUsers(
                    count = 5_000,
                    baseLongitude = -122.0841,
                    baseLatitude = 37.4220,
                )
                dataSource.analyzeGridPlannerTables()

                val repository = JdbcGridRepository(dataSource)
                val filterSet =
                    listOf(
                        DiscoveryFilters(),
                        DiscoveryFilters(relationshipContexts = setOf("single")),
                        DiscoveryFilters(relationshipContexts = setOf("linked")),
                        DiscoveryFilters(minimumAge = 35, maximumAge = 45),
                    )
                val timingsMillis =
                    coroutineScope {
                        (0 until 100)
                            .map { index ->
                                async(Dispatchers.IO) {
                                    val startedAt = System.nanoTime()
                                    val result =
                                        repository.queryNearby(
                                            viewerUserId = viewer,
                                            radiusMeters = 100_000,
                                            limit = 60,
                                            filters = filterSet[index % filterSet.size],
                                        )
                                    val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000
                                    assertTrue(result.viewerHadLocation)
                                    assertTrue(result.cards.isNotEmpty())
                                    elapsedMillis
                                }
                            }.awaitAll()
                    }
                val p95Millis = timingsMillis.sorted()[94]
                val maxMillis = timingsMillis.maxOrNull() ?: 0
                println("Grid dense 5000 candidate 100-concurrent filtered p95=${p95Millis}ms max=${maxMillis}ms")

                assertTrue(
                    p95Millis < 5_000,
                    "Dense concurrent grid p95 should stay below the local regression ceiling; actual=${p95Millis}ms",
                )
            }
        }
    })
