package com.pynapli.server.grid

import com.pynapli.server.distance.CoarseLocation
import com.pynapli.server.testsupport.PynapliTestDatabase
import com.pynapli.shared.network.dto.GridCardDto
import com.pynapli.shared.network.dto.GridQueryRequestDto
import io.kotest.core.spec.style.StringSpec
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Adversarial location-privacy proofs for the Grid (L-08, L-09, L-10, L-14). The attacker is an
 * ordinary account that can report any location and read every response field. Coordinates are
 * around 37.42 N, where a 0.01-degree cell is ~1.1 km north-south and ~0.88 km east-west.
 */
class GridLocationPrivacyTest :
    StringSpec({
        val dataSource = PynapliTestDatabase.dataSource

        beforeTest {
            PynapliTestDatabase.reset()
        }

        fun service(cacheTtl: Duration = Duration.ZERO): GridService = GridService(JdbcGridRepository(dataSource, rankCacheTtl = cacheTtl))

        // Requests a radius and returns every card the service shows (unrestricted tier). Since plan G10
        // a first page of fewer than 12 people widens to the 100 km step, which these small fixtures
        // trigger: service answers are then the 100 km answer, so radius MEMBERSHIP is also checked
        // straight from the repository (`ranked`), which never widens.
        suspend fun GridService.all(
            viewer: UUID,
            radius: Int,
        ): List<GridCardDto> = queryGrid(viewer, GridQueryRequestDto(radiusMeters = radius, limit = 120)).items

        suspend fun ranked(
            viewer: UUID,
            radius: Int,
        ): List<UUID> =
            JdbcGridRepository(dataSource, rankCacheTtl = Duration.ZERO)
                .queryNearby(viewer, CoarseLocation.snapRadiusMeters(radius), 120)
                .cards
                .map { it.userId }

        // Twelve people in the attacker's own cell, so every radius step shows at least 12 and the
        // service never widens: its answers then differ by step, as the snapping test needs.
        fun seedFillersAt(
            longitude: Double,
            latitude: Double,
        ) {
            (0 until GridService.SPARSE_FIRST_PAGE).forEach { i ->
                dataSource.insertDiscoverableUser(
                    UUID.fromString("00000000-0000-0000-0000-00000000f%03d".format(i)),
                    "Filler $i",
                    age = 30,
                    longitude = longitude + i * 0.0002,
                    latitude = latitude,
                )
            }
        }

        val attacker = UUID.fromString("00000000-0000-0000-0000-00000000a001")
        val hiddenOne = UUID.fromString("00000000-0000-0000-0000-00000000a002")
        val hiddenTwo = UUID.fromString("00000000-0000-0000-0000-00000000a003")
        // Attacker positions: south, east and north-west of the hidden pair, each in a different cell.
        val attackerPositions = listOf(-122.08 to 37.40, -122.05 to 37.42, -122.12 to 37.45)

        fun seedNeighbourhood() {
            dataSource.insertDiscoverableUser(attacker, "Attacker", age = 30, longitude = attackerPositions[0].first, latitude = attackerPositions[0].second)
            // Two hidden-distance users 300 m apart, in the same cell.
            dataSource.insertDiscoverableUser(hiddenOne, "Hidden One", age = 30, longitude = -122.0800, latitude = 37.4190)
            dataSource.insertDiscoverableUser(hiddenTwo, "Hidden Two", age = 30, longitude = -122.0800, latitude = 37.4217)
            dataSource.setShowDistance(hiddenOne, false)
            dataSource.setShowDistance(hiddenTwo, false)
            // Distance-showing neighbours that could otherwise bracket the hidden pair.
            listOf(37.4150, 37.4185, 37.4200, 37.4222, 37.4260, 37.4400, 37.4700).forEachIndexed { i, lat ->
                dataSource.insertDiscoverableUser(
                    UUID.fromString("00000000-0000-0000-0000-00000000b%03d".format(i)),
                    "Shown $i",
                    age = 30,
                    longitude = -122.0805 + i * 0.0003,
                    latitude = lat,
                )
            }
        }

        "two hidden-distance users 300 m apart are indistinguishable from every attacker position and radius step" {
            runBlocking {
                seedNeighbourhood()
                val grid = service()
                attackerPositions.forEach { (lon, lat) ->
                    dataSource.moveUser(attacker, longitude = lon, latitude = lat)
                    listOf(1_000, 2_500, 4_000, 7_000, 20_000, 60_000).forEach { radius ->
                        val cards = grid.all(attacker, radius)
                        val one = cards.firstOrNull { it.userId == hiddenOne.toString() }
                        val two = cards.firstOrNull { it.userId == hiddenTwo.toString() }
                        assertEquals(one == null, two == null, "membership differs at $lon,$lat r=$radius")
                        // The same at the rank itself, which never widens (plan G10).
                        val rankedIds = ranked(attacker, radius)
                        assertEquals(hiddenOne in rankedIds, hiddenTwo in rankedIds, "ranked membership differs at $lon,$lat r=$radius")
                        if (one != null && two != null) {
                            fun GridCardDto.observable() = copy(userId = "", displayName = "", rankToken = "")
                            assertEquals(one.observable(), two.observable(), "fields differ at $lon,$lat r=$radius")
                            assertEquals(null, one.distanceMeters)
                            assertEquals(null, one.distanceLabel)
                        }
                    }
                }
            }
        }

        "a hidden-distance user's place in the list does not depend on where they are" {
            runBlocking {
                seedNeighbourhood()
                val grid = service()
                attackerPositions.forEach { (lon, lat) ->
                    dataSource.moveUser(attacker, longitude = lon, latitude = lat)
                    dataSource.moveUser(hiddenOne, longitude = -122.0800, latitude = 37.4190)
                    val before = grid.all(attacker, 25_000).map { it.userId }
                    // 300 m north into the next cell, then 4 km away: the order must not move.
                    dataSource.moveUser(hiddenOne, longitude = -122.0800, latitude = 37.4257)
                    val after300m = grid.all(attacker, 25_000).map { it.userId }
                    dataSource.moveUser(hiddenOne, longitude = -122.0400, latitude = 37.4400)
                    val after4km = grid.all(attacker, 25_000).map { it.userId }
                    assertTrue(hiddenOne.toString() in before)
                    assertEquals(before, after300m, "hidden user moved 300 m and the order changed (attacker at $lon,$lat)")
                    assertEquals(before, after4km, "hidden user moved 4 km and the order changed (attacker at $lon,$lat)")
                }
            }
        }

        "the attacker moving 30 m at a time inside a cell changes no field and no order" {
            runBlocking {
                seedNeighbourhood()
                val grid = service()
                val readings =
                    (0..4).map { step ->
                        dataSource.moveUser(attacker, longitude = -122.0800 + step * 0.00034, latitude = 37.4000 - step * 0.00027)
                        grid.all(attacker, 25_000)
                    }
                assertTrue(readings.first().isNotEmpty())
                readings.forEach { assertEquals(readings.first(), it, "a 30 m attacker step changed the Grid") }
            }
        }

        "radius is snapped to fixed steps, so radii between two steps return identical results" {
            runBlocking {
                seedNeighbourhood()
                dataSource.moveUser(attacker, longitude = -122.05, latitude = 37.42)
                seedFillersAt(longitude = -122.0502, latitude = 37.4201)
                val grid = service()
                val byStep = CoarseLocation.RADIUS_STEPS_METERS.associateWith { grid.all(attacker, it).map { c -> c.userId } }
                (1_000..100_000 step 1_700).forEach { requested ->
                    val expected = byStep.getValue(CoarseLocation.snapRadiusMeters(requested))
                    assertEquals(expected, grid.all(attacker, requested).map { it.userId }, "radius $requested leaked a non-step membership")
                }
                assertEquals(listOf(2_000, 2_000, 5_000, 5_000, 10_000, 100_000), listOf(1_000, 2_000, 2_001, 5_000, 5_001, 250_000).map(CoarseLocation::snapRadiusMeters))
            }
        }

        "a traveller's home distance never appears and they are matched only where they travel" {
            runBlocking {
                val traveller = UUID.fromString("00000000-0000-0000-0000-00000000a0f1")
                dataSource.insertDiscoverableUser(attacker, "Attacker", age = 30, longitude = -122.08, latitude = 37.42)
                // Home ~880 m from the attacker; travelling ~45 km away.
                dataSource.insertDiscoverableUser(traveller, "Traveller", age = 30, longitude = -122.07, latitude = 37.42)
                dataSource.insertActiveTravelLocation(traveller, "Elsewhere", latitude = 37.80, longitude = -122.27)
                val grid = service()
                val repository = JdbcGridRepository(dataSource, rankCacheTtl = Duration.ZERO)
                CoarseLocation.RADIUS_STEPS_METERS.forEach { radius ->
                    // Membership straight from the repository (the service would widen an empty page).
                    val matched = repository.queryNearby(attacker, radius, 120).cards.any { it.userId == traveller }
                    assertEquals(radius >= 50_000, matched, "traveller membership at r=$radius")
                    // Whatever the service shows (including a widened page), the distance is the travel point's.
                    grid.all(attacker, radius).firstOrNull { it.userId == traveller.toString() }?.let { shown ->
                        assertTrue(shown.visiting)
                        assertTrue(assertNotNull(shown.distanceMeters) >= 40_000, "home distance leaked: ${shown.distanceMeters}")
                    }
                }
            }
        }

        "the swipe deck order of a hidden-distance user does not depend on where they are" {
            runBlocking {
                seedNeighbourhood()
                val deck =
                    com.pynapli.server.swipe
                        .JdbcSwipeRepository(dataSource, rankCacheTtl = Duration.ZERO)
                attackerPositions.forEach { (lon, lat) ->
                    dataSource.moveUser(attacker, longitude = lon, latitude = lat)
                    dataSource.moveUser(hiddenOne, longitude = -122.0800, latitude = 37.4190)
                    val before = deck.queryDeck(attacker, 25_000, 50).cards.map { it.userId }
                    dataSource.moveUser(hiddenOne, longitude = -122.0400, latitude = 37.4400)
                    val after = deck.queryDeck(attacker, 25_000, 50).cards.map { it.userId }
                    assertTrue(hiddenOne in before)
                    assertEquals(before, after, "hidden user moved 4 km and the deck order changed (attacker at $lon,$lat)")
                }
            }
        }

        "a traveller's home distance never appears on a swipe card" {
            runBlocking {
                val traveller = UUID.fromString("00000000-0000-0000-0000-00000000a0f2")
                dataSource.insertDiscoverableUser(attacker, "Attacker", age = 30, longitude = -122.08, latitude = 37.42)
                dataSource.insertDiscoverableUser(traveller, "Traveller", age = 30, longitude = -122.07, latitude = 37.42)
                dataSource.insertActiveTravelLocation(traveller, "Elsewhere", latitude = 37.80, longitude = -122.27)
                // Enough locals that the cached deck takes the nearest-candidate window.
                (0 until 12).forEach { i ->
                    dataSource.insertDiscoverableUser(
                        UUID.fromString("00000000-0000-0000-0000-00000000e%03d".format(i)),
                        "Local $i",
                        age = 30,
                        longitude = -122.081 + i * 0.001,
                        latitude = 37.421,
                    )
                }
                listOf(Duration.ZERO, Duration.ofMinutes(5)).forEach { ttl ->
                    val cards =
                        com.pynapli.server.swipe
                            .JdbcSwipeRepository(dataSource, rankCacheTtl = ttl)
                            .queryDeck(attacker, 25_000, 10)
                            .cards
                    cards.firstOrNull { it.userId == traveller }?.let { dealt ->
                        assertTrue(dealt.visiting, "traveller dealt as a local (ttl=$ttl)")
                        assertTrue(dealt.distanceMeters >= 40_000, "home distance leaked (ttl=$ttl): ${dealt.distanceMeters}")
                    }
                }
            }
        }

        "paging 12 at a time through mixed hidden, shown and boosted users never skips or duplicates, cached or not" {
            runBlocking {
                dataSource.insertDiscoverableUser(attacker, "Attacker", age = 30, longitude = -122.08, latitude = 37.42)
                val everyone =
                    (0 until 41).map { i ->
                        val id = UUID.fromString("00000000-0000-0000-0000-00000000c%03d".format(i))
                        dataSource.insertDiscoverableUser(id, "P$i", age = 30, longitude = -122.08 + (i % 7) * 0.004, latitude = 37.42 + (i / 7) * 0.003)
                        if (i % 3 == 0) dataSource.setShowDistance(id, false)
                        if (i % 11 == 0) dataSource.insertBoostActivation(id)
                        id.toString()
                    }

                suspend fun walk(grid: GridService): List<String> {
                    val seen = mutableListOf<String>()
                    var cursor: String? = null
                    var pages = 0
                    do {
                        val page = grid.queryGrid(attacker, GridQueryRequestDto(radiusMeters = 25_000, limit = 1, cursor = cursor))
                        assertTrue(page.items.size == 12 || page.nextCursor == null, "non-final page of ${page.items.size}")
                        seen += page.items.map { it.userId }
                        cursor = page.nextCursor
                        pages++
                    } while (cursor != null && pages < 20)
                    return seen
                }

                val uncached = walk(service())
                val cachedSmallWindow = walk(GridService(JdbcGridRepository(dataSource, rankCacheTtl = Duration.ofMinutes(5), rankCacheWindow = 16)))
                assertEquals(uncached.distinct(), uncached, "duplicate card while paging")
                assertEquals(everyone.toSet(), uncached.toSet(), "skipped card while paging")
                assertEquals(uncached, cachedSmallWindow, "cached paging must match uncached paging exactly")
            }
        }
    })

internal fun DataSource.setShowDistance(
    userId: UUID,
    showDistance: Boolean,
) {
    connection.use { connection ->
        connection.prepareStatement("UPDATE discovery_settings SET show_distance = ? WHERE user_id = ?").use { statement ->
            statement.setBoolean(1, showDistance)
            statement.setObject(2, userId)
            statement.executeUpdate()
        }
    }
}
