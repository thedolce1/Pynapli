package com.pynapli.server.swipe

import com.pynapli.server.activity.ActivityService
import com.pynapli.server.activity.JdbcActivityRepository
import com.pynapli.server.distance.CoarseLocation
import com.pynapli.server.distance.toPublicDistanceMeters
import com.pynapli.server.grid.insertActiveTravelLocation
import com.pynapli.server.grid.insertDiscoverableUser
import com.pynapli.server.grid.moveUser
import com.pynapli.server.grid.setShowDistance
import com.pynapli.server.testsupport.PynapliTestDatabase
import com.pynapli.shared.network.dto.SwipeAction
import com.pynapli.shared.network.dto.SwipeCardDto
import com.pynapli.shared.network.dto.SwipeDealbreakerKey
import com.pynapli.shared.network.dto.SwipeDeckRequestDto
import io.kotest.core.spec.style.StringSpec
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Adversarial location-privacy proofs for the Swipe deck, rewound cards and Activity rows (the
 * Swipe side of L-08..L-14, mirroring GridLocationPrivacyTest). The attacker is an ordinary account
 * that can report any location, pick any radius and read every response field. Coordinates are
 * around 37.42 N, where a 0.01-degree cell is ~1.1 km north-south and ~0.88 km east-west.
 */
class SwipeLocationPrivacyTest :
    StringSpec({
        val dataSource = PynapliTestDatabase.dataSource

        beforeTest {
            PynapliTestDatabase.reset()
        }

        fun service(cacheTtl: Duration = Duration.ZERO): SwipeService = SwipeService(JdbcSwipeRepository(dataSource, rankCacheTtl = cacheTtl))

        // Every card in one deck page. A distance dealbreaker keeps the deck from widening its radius
        // when it is thin, so membership is exactly the requested circle.
        suspend fun SwipeService.all(
            viewer: UUID,
            radius: Int,
        ): List<SwipeCardDto> =
            queryDeck(
                viewer,
                SwipeDeckRequestDto(radiusMeters = radius, limit = 50, dealbreakers = setOf(SwipeDealbreakerKey.DISTANCE)),
            ).items

        // What an attacker can compare between two cards (identity and per-session tokens aside).
        fun SwipeCardDto.observable() = copy(userId = "", displayName = "", rankToken = "")

        val attacker = UUID.fromString("00000000-0000-0000-0000-00000000d001")
        val hiddenOne = UUID.fromString("00000000-0000-0000-0000-00000000d002")
        val hiddenTwo = UUID.fromString("00000000-0000-0000-0000-00000000d003")
        val attackerPositions = listOf(-122.08 to 37.40, -122.05 to 37.42, -122.12 to 37.45)

        fun seedNeighbourhood() {
            dataSource.insertDiscoverableUser(attacker, "Attacker", 30, attackerPositions[0].first, attackerPositions[0].second)
            // Two hidden-distance users 300 m apart, in the same cell.
            dataSource.insertDiscoverableUser(hiddenOne, "Hidden One", 30, -122.0800, 37.4190)
            dataSource.insertDiscoverableUser(hiddenTwo, "Hidden Two", 30, -122.0800, 37.4217)
            dataSource.setShowDistance(hiddenOne, false)
            dataSource.setShowDistance(hiddenTwo, false)
            listOf(37.4150, 37.4185, 37.4200, 37.4222, 37.4260, 37.4400, 37.4700).forEachIndexed { i, lat ->
                dataSource.insertDiscoverableUser(
                    UUID.fromString("00000000-0000-0000-0000-00000000e%03d".format(i)),
                    "Shown $i",
                    30,
                    -122.0805 + i * 0.0003,
                    lat,
                )
            }
        }

        "two hidden-distance users 300 m apart are indistinguishable in the deck from every attacker position and radius" {
            runBlocking {
                seedNeighbourhood()
                listOf(Duration.ZERO, Duration.ofMinutes(5)).forEach { ttl ->
                    val deck = service(ttl)
                    attackerPositions.forEach { (lon, lat) ->
                        dataSource.moveUser(attacker, lon, lat)
                        listOf(1_000, 2_200, 2_500, 4_000, 7_000, 20_000, 60_000).forEach { radius ->
                            val cards = deck.all(attacker, radius)
                            val one = cards.firstOrNull { it.userId == hiddenOne.toString() }
                            val two = cards.firstOrNull { it.userId == hiddenTwo.toString() }
                            assertEquals(one == null, two == null, "membership differs at $lon,$lat r=$radius ttl=$ttl")
                            if (one != null && two != null) {
                                assertEquals(one.observable(), two.observable(), "fields differ at $lon,$lat r=$radius")
                                assertNull(one.distanceMeters)
                                assertNull(one.distanceLabel)
                            }
                        }
                    }
                }
            }
        }

        "the attacker moving 30 m at a time inside a cell changes no deck field and no order" {
            runBlocking {
                seedNeighbourhood()
                listOf(Duration.ZERO, Duration.ofMinutes(5)).forEach { ttl ->
                    val deck = service(ttl)
                    val readings =
                        (0..4).map { step ->
                            dataSource.moveUser(attacker, -122.0800 + step * 0.00034, 37.4000 - step * 0.00027)
                            deck.all(attacker, 25_000).map { it.observable().copy(displayName = it.displayName) }
                        }
                    assertTrue(readings.first().isNotEmpty())
                    readings.forEach { assertEquals(readings.first(), it, "a 30 m attacker step changed the deck (ttl=$ttl)") }
                }
            }
        }

        "a requested radius returns exactly its radius step" {
            runBlocking {
                seedNeighbourhood()
                dataSource.moveUser(attacker, -122.05, 37.42)
                val deck = service()
                val byStep = CoarseLocation.RADIUS_STEPS_METERS.associateWith { deck.all(attacker, it).map { c -> c.userId }.toSet() }
                (1_000..100_000 step 1_700).forEach { requested ->
                    val expected = byStep.getValue(CoarseLocation.snapRadiusMeters(requested))
                    assertEquals(expected, deck.all(attacker, requested).map { it.userId }.toSet(), "radius $requested leaked a non-step membership")
                }
            }
        }

        "a traveller is dealt only where they travel, at the travel point's distance, cached or not" {
            runBlocking {
                val traveller = UUID.fromString("00000000-0000-0000-0000-00000000d0f1")
                dataSource.insertDiscoverableUser(attacker, "Attacker", 30, -122.08, 37.42)
                // Home ~880 m from the attacker; travelling ~45 km away.
                dataSource.insertDiscoverableUser(traveller, "Traveller", 30, -122.07, 37.42)
                dataSource.insertActiveTravelLocation(traveller, "Elsewhere", latitude = 37.80, longitude = -122.27)
                (0 until 12).forEach { i ->
                    dataSource.insertDiscoverableUser(UUID.fromString("00000000-0000-0000-0000-00000000f%03d".format(i)), "Local $i", 30, -122.081 + i * 0.001, 37.421)
                }
                listOf(Duration.ZERO, Duration.ofMinutes(5)).forEach { ttl ->
                    val repository = JdbcSwipeRepository(dataSource, rankCacheTtl = ttl)
                    CoarseLocation.RADIUS_STEPS_METERS.forEach { radius ->
                        val dealt = repository.queryDeck(attacker, radius, 50).cards.firstOrNull { it.userId == traveller }
                        assertEquals(radius >= 50_000, dealt != null, "traveller membership at r=$radius ttl=$ttl")
                        dealt?.let {
                            assertTrue(it.visiting)
                            assertTrue(it.distanceMeters >= 40_000, "home distance leaked: ${it.distanceMeters}")
                        }
                    }
                }
            }
        }

        "a travelling viewer's deck is centred on where they travel" {
            runBlocking {
                val viewer = UUID.fromString("00000000-0000-0000-0000-00000000d0f2")
                val homeNeighbour = UUID.fromString("00000000-0000-0000-0000-00000000d0f3")
                val travelNeighbour = UUID.fromString("00000000-0000-0000-0000-00000000d0f4")
                dataSource.insertDiscoverableUser(viewer, "Viewer", 30, -122.08, 37.42)
                dataSource.insertDiscoverableUser(homeNeighbour, "Home", 30, -122.081, 37.421)
                dataSource.insertDiscoverableUser(travelNeighbour, "There", 30, -122.271, 37.801)
                dataSource.insertActiveTravelLocation(viewer, "Elsewhere", latitude = 37.80, longitude = -122.27)

                val ids = service().all(viewer, 5_000).map { it.userId }

                assertEquals(listOf(travelNeighbour.toString()), ids)
            }
        }

        "paging through a deck in small pages never skips or repeats anyone, cached or not" {
            runBlocking {
                dataSource.insertDiscoverableUser(attacker, "Attacker", 30, -122.08, 37.42)
                val everyone =
                    (0 until 41).map { i ->
                        val id = UUID.fromString("00000000-0000-0000-0000-00000000c%03d".format(i))
                        dataSource.insertDiscoverableUser(id, "P$i", 30, -122.08 + (i % 7) * 0.004, 37.42 + (i / 7) * 0.003)
                        if (i % 3 == 0) dataSource.setShowDistance(id, false)
                        id.toString()
                    }

                suspend fun walk(deck: SwipeService): List<String> {
                    val seen = mutableListOf<String>()
                    var cursor: String? = null
                    var pages = 0
                    do {
                        val page = deck.queryDeck(attacker, SwipeDeckRequestDto(radiusMeters = 25_000, limit = 5, cursor = cursor))
                        seen += page.items.map { it.userId }
                        cursor = page.nextCursor
                        pages++
                    } while (cursor != null && pages < 30)
                    return seen
                }

                listOf(service(), SwipeService(JdbcSwipeRepository(dataSource, rankCacheTtl = Duration.ofMinutes(5), rankCacheWindow = 8))).forEach { deck ->
                    val seen = walk(deck)
                    assertEquals(seen.distinct(), seen, "a card was dealt twice while paging")
                    assertEquals(everyone.toSet(), seen.toSet(), "a card was skipped while paging")
                }
            }
        }

        "a rewound card shows a coarse distance and none when its owner hides it" {
            runBlocking {
                val viewer = UUID.fromString("00000000-0000-0000-0000-00000000d0a1")
                val shown = UUID.fromString("00000000-0000-0000-0000-00000000d0a2")
                val hidden = UUID.fromString("00000000-0000-0000-0000-00000000d0a3")
                dataSource.insertDiscoverableUser(viewer, "Viewer", 30, -122.0800, 37.4000)
                dataSource.insertDiscoverableUser(shown, "Shown", 30, -122.0800, 37.4190)
                dataSource.insertDiscoverableUser(hidden, "Hidden", 30, -122.0800, 37.4217)
                dataSource.setShowDistance(hidden, false)
                val repository = JdbcSwipeRepository(dataSource)

                repository.recordSwipeAction(UUID.randomUUID(), viewer, shown, SwipeAction.PASS)
                val readings =
                    (0..3).map { step ->
                        dataSource.moveUser(viewer, -122.0800 + step * 0.00034, 37.4000 - step * 0.00027)
                        assertNotNull(repository.findLatestRewindableAction(viewer, SWIPE_MAX_RADIUS_METERS)).card.distanceMeters
                    }
                // Cell to cell, so a 30 m step never changes it.
                readings.forEach { assertEquals(readings.first(), it) }
                assertEquals(dataSource.coarseDistance(viewer, shown), readings.first(), 0.5)

                repository.recordSwipeAction(UUID.randomUUID(), viewer, hidden, SwipeAction.PASS)
                val hiddenCard = assertNotNull(repository.findLatestRewindableAction(viewer, SWIPE_MAX_RADIUS_METERS)).card
                assertEquals(hidden, hiddenCard.userId)
                val dto = SwipeDtoMapper(null).cardToDto(hiddenCard, rankToken = "r", online = false, onlineUserIds = emptySet())
                assertNull(dto.distanceMeters)
                assertNull(dto.distanceLabel)
            }
        }

        "an Activity row's distance is coarse, from where the person travels, and absent when they hide it" {
            runBlocking {
                val recipient = UUID.fromString("00000000-0000-0000-0000-00000000d0b1")
                val shown = UUID.fromString("00000000-0000-0000-0000-00000000d0b2")
                val hidden = UUID.fromString("00000000-0000-0000-0000-00000000d0b3")
                val traveller = UUID.fromString("00000000-0000-0000-0000-00000000d0b4")
                dataSource.insertDiscoverableUser(recipient, "Recipient", 30, -122.0800, 37.4000)
                dataSource.insertDiscoverableUser(shown, "Shown", 30, -122.0800, 37.4600)
                dataSource.insertDiscoverableUser(hidden, "Hidden", 30, -122.0800, 37.4217)
                dataSource.insertDiscoverableUser(traveller, "Traveller", 30, -122.0810, 37.4010)
                dataSource.setShowDistance(hidden, false)
                dataSource.insertActiveTravelLocation(traveller, "Elsewhere", latitude = 37.80, longitude = -122.27)
                val activity = ActivityService(JdbcActivityRepository(dataSource, contactBlockTokens = com.pynapli.server.contactblocks.ContactBlockTokens.localDevelopment()))
                listOf(shown, hidden, traveller).forEach { activity.recordSwipeInterest(it, recipient, "swipe_like") }

                val readings =
                    (0..3).map { step ->
                        dataSource.moveUser(recipient, -122.0800 + step * 0.00034, 37.4000 - step * 0.00027)
                        activity.listActivity(recipient).items.associate { it.actorUserId to it.distanceMeters }
                    }
                readings.forEach { assertEquals(readings.first(), it, "a 30 m step changed an Activity distance") }
                val distances = readings.first()
                assertEquals(dataSource.coarseDistance(recipient, shown).toPublicDistanceMeters(), distances[shown.toString()])
                assertNull(distances[hidden.toString()])
                // The traveller's home is ~150 m away; only the travel point's distance may show.
                assertTrue(assertNotNull(distances[traveller.toString()]) >= 40_000, "traveller's home distance leaked")
            }
        }
    })

private fun DataSource.coarseDistance(
    a: UUID,
    b: UUID,
): Double =
    connection.use { c ->
        c
            .prepareStatement(
                """
                SELECT pynapli_coarse_distance_m(la.geom, lb.geom)
                FROM user_locations la, user_locations lb
                WHERE la.user_id = ? AND lb.user_id = ?
                """.trimIndent(),
            ).use { s ->
                s.setObject(1, a)
                s.setObject(2, b)
                s.executeQuery().use { rs ->
                    rs.next()
                    rs.getDouble(1)
                }
            }
    }
