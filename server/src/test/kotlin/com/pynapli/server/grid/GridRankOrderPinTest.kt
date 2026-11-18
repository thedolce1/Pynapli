package com.pynapli.server.grid

import com.pynapli.server.discovery.DiscoveryFilters
import com.pynapli.server.testsupport.PynapliTestDatabase
import io.kotest.core.spec.style.StringSpec
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pins the full ordered output of the Grid rank for a mixed set (shown and hidden distance,
 * boosted, verified, travellers in and out of range). The expected order is built independently
 * from the V94/V95 helpers called one user at a time, so this test does not depend on how the rank
 * query is written: any rewrite of it must return the same rows, values and order, cached,
 * uncached and paged.
 */
class GridRankOrderPinTest :
    StringSpec({
        val dataSource = PynapliTestDatabase.dataSource

        beforeTest {
            PynapliTestDatabase.reset()
        }

        fun id(n: Int): UUID = UUID.fromString("00000000-0000-0000-0000-00000000d%03d".format(n))

        val radius = 25_000
        val viewer = id(0)
        val shownNear = id(1)
        val shownMid = id(2)
        val shownFar = id(3)
        val shownVerified = id(4)
        val boosted = id(5)
        val hiddenOne = id(6)
        val hiddenTwo = id(7)
        val travellerHere = id(8)
        val travellerAway = id(9)
        val outOfRange = id(10)
        val inRange = listOf(shownNear, shownMid, shownFar, shownVerified, boosted, hiddenOne, hiddenTwo, travellerHere)

        fun seed() {
            dataSource.insertDiscoverableUser(viewer, "Viewer", age = 30, longitude = -122.0797, latitude = 37.4203)
            dataSource.insertDiscoverableUser(shownNear, "Near", age = 30, longitude = -122.0803, latitude = 37.4297)
            dataSource.insertDiscoverableUser(shownMid, "Mid", age = 30, longitude = -122.0797, latitude = 37.4703)
            dataSource.insertDiscoverableUser(shownFar, "Far", age = 30, longitude = -122.0803, latitude = 37.5503)
            dataSource.insertDiscoverableUser(shownVerified, "Verified", age = 30, longitude = -122.0397, latitude = 37.4503)
            dataSource.markProfileVerified(shownVerified)
            // Boosted and further away than everyone else in range: still first.
            dataSource.insertDiscoverableUser(boosted, "Boosted", age = 30, longitude = -122.0803, latitude = 37.5903)
            dataSource.insertBoostActivation(boosted)
            dataSource.insertDiscoverableUser(hiddenOne, "Hidden One", age = 30, longitude = -122.0797, latitude = 37.4397)
            dataSource.insertDiscoverableUser(hiddenTwo, "Hidden Two", age = 30, longitude = -122.1497, latitude = 37.5003)
            dataSource.setShowDistance(hiddenOne, false)
            dataSource.setShowDistance(hiddenTwo, false)
            // Home ~130 km away, travelling ~4.4 km from the viewer: matched and measured at the travel point.
            dataSource.insertDiscoverableUser(travellerHere, "Visitor", age = 30, longitude = -122.0797, latitude = 38.6003)
            dataSource.insertActiveTravelLocation(travellerHere, "Here", latitude = 37.4603, longitude = -122.0797)
            // Home ~1 km away, travelling ~45 km away: never matched by home.
            dataSource.insertDiscoverableUser(travellerAway, "Away", age = 30, longitude = -122.0797, latitude = 37.4303)
            dataSource.insertActiveTravelLocation(travellerAway, "Elsewhere", latitude = 37.8003, longitude = -122.2697)
            dataSource.insertDiscoverableUser(outOfRange, "Out", age = 30, longitude = -122.0797, latitude = 37.7303)
        }

        data class Pinned(
            val userId: UUID,
            val showDistance: Boolean,
            val boostBucket: Int,
            val orderBucket: Int,
            val orderTiebreak: Long,
            val distanceMeters: Double,
            val visiting: Boolean,
        )

        fun GridCardRecord.pinned() = Pinned(userId, showDistance, boostBucket, orderBucket, orderTiebreak, distanceMeters, visiting)

        // Reference: the V94/V95 helpers evaluated per user, sorted in Kotlin by the documented key
        // (boost, order bucket, tiebreak, user id). Postgres orders uuid by bytes, which is the order
        // of the lowercase hex string.
        fun expected(): List<Pinned> {
            val seed =
                dataSource.connection.use { connection ->
                    assertNotNull(connection.findGridViewerLocationCacheKey(viewer)).orderSeed
                }
            val rows =
                dataSource.connection.use { connection ->
                    connection
                        .prepareStatement(
                            """
                            WITH v AS (
                                SELECT pynapli_location_cell(COALESCE(tl.geom, ul.geom)) AS geom
                                FROM user_locations ul
                                LEFT JOIN travel_locations tl
                                  ON tl.user_id = ul.user_id AND tl.status = 'active' AND tl.active_until IS NULL
                                WHERE ul.user_id = ?
                            ),
                            t AS (
                                SELECT ul.user_id,
                                       ds.show_distance,
                                       pynapli_coarse_distance_m(v.geom, COALESCE(tt.geom, ul.geom)) AS d,
                                       tt.id IS NOT NULL AS visiting
                                FROM v
                                CROSS JOIN user_locations ul
                                JOIN discovery_settings ds ON ds.user_id = ul.user_id
                                LEFT JOIN travel_locations tt
                                  ON tt.user_id = ul.user_id AND tt.status = 'active' AND tt.active_until IS NULL
                                WHERE ul.user_id = ANY(?::uuid[])
                            )
                            SELECT user_id,
                                   show_distance,
                                   d,
                                   visiting,
                                   pynapli_order_bucket_m(show_distance, d, ?, ?, user_id) AS bucket,
                                   pynapli_order_tiebreak(?, user_id) AS tiebreak
                            FROM t
                            """.trimIndent(),
                        ).use { statement ->
                            statement.setObject(1, viewer)
                            statement.setArray(2, connection.createArrayOf("uuid", inRange.toTypedArray()))
                            statement.setInt(3, radius)
                            statement.setString(4, seed)
                            statement.setString(5, seed)
                            statement.executeQuery().use { rs ->
                                buildList {
                                    while (rs.next()) {
                                        val userId = rs.getObject("user_id", UUID::class.java)
                                        add(
                                            Pinned(
                                                userId = userId,
                                                showDistance = rs.getBoolean("show_distance"),
                                                boostBucket = if (userId == boosted) 0 else 1,
                                                orderBucket = rs.getInt("bucket"),
                                                orderTiebreak = rs.getLong("tiebreak"),
                                                distanceMeters = rs.getDouble("d"),
                                                visiting = rs.getBoolean("visiting"),
                                            ),
                                        )
                                    }
                                }
                            }
                        }
                }
            assertEquals(inRange.toSet(), rows.map { it.userId }.toSet())
            return rows.sortedWith(compareBy<Pinned>({ it.boostBucket }, { it.orderBucket }, { it.orderTiebreak }, { it.userId.toString() }))
        }

        suspend fun JdbcGridRepository.walk(pageSize: Int): List<Pinned> {
            val seen = mutableListOf<Pinned>()
            var cursor: GridCursorAnchor? = null
            var pages = 0
            do {
                val page = queryNearby(viewer, radius, pageSize, DiscoveryFilters(), cursor)
                seen += page.cards.map { it.pinned() }
                cursor = page.nextCursorAnchor
                pages++
            } while (cursor != null && pages < 20)
            return seen
        }

        "the grid rank returns the same rows, values and order as the coarse helpers, cached, uncached and paged" {
            runBlocking {
                seed()
                val want = expected()

                // Order-independent facts first, so a wrong reference cannot hide a wrong answer.
                assertEquals(boosted, want.first().userId)
                val shown = want.filter { it.showDistance && it.boostBucket == 1 }.map { it.orderBucket }
                assertEquals(shown.sorted(), shown, "shown-distance users must ascend by whole-km bucket")
                val visitor = want.single { it.userId == travellerHere }
                assertTrue(visitor.visiting)
                assertTrue(visitor.distanceMeters in 3_000.0..6_000.0, "visitor measured at home: ${visitor.distanceMeters}")
                assertFalse(want.any { it.userId == travellerAway || it.userId == outOfRange || it.userId == viewer })

                val uncached = JdbcGridRepository(dataSource, rankCacheTtl = Duration.ZERO)
                val cached = JdbcGridRepository(dataSource, rankCacheTtl = Duration.ofMinutes(5))
                val cachedSmallWindow = JdbcGridRepository(dataSource, rankCacheTtl = Duration.ofMinutes(5), rankCacheWindow = 4)

                assertEquals(want, uncached.queryNearby(viewer, radius, 120).cards.map { it.pinned() }, "uncached")
                assertEquals(want, cached.queryNearby(viewer, radius, 120).cards.map { it.pinned() }, "cached")
                assertEquals(want, uncached.walk(pageSize = 3), "uncached, paged 3 at a time")
                assertEquals(want, cachedSmallWindow.walk(pageSize = 3), "cached window of 4, paged 3 at a time")
            }
        }
    })
