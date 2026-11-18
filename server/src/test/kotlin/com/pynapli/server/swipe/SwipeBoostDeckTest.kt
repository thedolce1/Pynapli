package com.pynapli.server.swipe

import com.pynapli.server.grid.insertBoostActivation
import com.pynapli.server.grid.insertDiscoverableUser
import com.pynapli.server.safety.JdbcBlockRepository
import com.pynapli.server.testsupport.PynapliTestDatabase
import com.pynapli.shared.network.dto.SwipeAction
import com.pynapli.shared.network.dto.SwipeDealbreakerKey
import com.pynapli.shared.network.dto.SwipeDeckRequestDto
import io.kotest.core.spec.style.StringSpec
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A Boost is a real lift in Swipe (MONETIZATION_SPEC "Boost"): a boosted person who passes every
 * rule and is inside the viewer's radius step is pulled onto the first page even when the deck
 * would otherwise reach them pages later, at most one lifted card in every four.
 */
class SwipeBoostDeckTest :
    StringSpec({
        val dataSource = PynapliTestDatabase.dataSource

        beforeTest {
            PynapliTestDatabase.reset()
        }

        val viewer = UUID.fromString("00000000-0000-0000-0000-0000000b0001")
        val ttls = listOf(Duration.ZERO, Duration.ofMinutes(5))

        fun service(ttl: Duration) =
            SwipeService(
                repository = JdbcSwipeRepository(dataSource, rankCacheTtl = ttl),
                blockRepository = JdbcBlockRepository(dataSource),
            )

        fun request(
            limit: Int = 10,
            cursor: String? = null,
            maximumAge: Int? = null,
        ) = SwipeDeckRequestDto(
            radiusMeters = 25_000,
            limit = limit,
            cursor = cursor,
            maximumAge = maximumAge,
            dealbreakers = setOf(SwipeDealbreakerKey.DISTANCE, SwipeDealbreakerKey.AGE),
        )

        // Thirty people within about a kilometre of the viewer: three pages of ten before anyone far.
        fun seedLocals() {
            dataSource.insertDiscoverableUser(viewer, "Viewer", 30, -79.38, 43.65)
            (0 until 30).forEach { i ->
                dataSource.insertDiscoverableUser(
                    UUID.fromString("00000000-0000-0000-0000-0000000b1%03d".format(i)),
                    "Local $i",
                    30,
                    -79.38 + (i % 6) * 0.001,
                    43.65 + (i / 6) * 0.001,
                )
            }
        }

        // About 20 km north of the viewer: inside the 25 km step, but last in distance order.
        fun seedFar(
            id: UUID,
            age: Int = 30,
            latitudeOffset: Double = 0.18,
        ) = dataSource.insertDiscoverableUser(id, "Far", age, -79.38, 43.65 + latitudeOffset)

        "a boosted person who would be pages away is dealt on the first page" {
            runBlocking {
                seedLocals()
                val boosted = UUID.fromString("00000000-0000-0000-0000-0000000b2001")
                val control = UUID.fromString("00000000-0000-0000-0000-0000000b2002")
                seedFar(boosted)
                seedFar(control)
                dataSource.insertBoostActivation(boosted)

                ttls.forEach { ttl ->
                    val page = service(ttl).queryDeck(viewer, request()).items.map { it.userId }
                    assertEquals(boosted.toString(), page.first(), "boosted person not at the top (ttl=$ttl): $page")
                    assertFalse(control.toString() in page, "the same person without a Boost should be pages away (ttl=$ttl)")
                }
            }
        }

        "at most one lifted Boost in every four cards" {
            runBlocking {
                seedLocals()
                val boosted = (0 until 8).map { UUID.fromString("00000000-0000-0000-0000-0000000b3%03d".format(it)) }
                boosted.forEachIndexed { i, id ->
                    seedFar(id, latitudeOffset = 0.15 + i * 0.005)
                    dataSource.insertBoostActivation(id)
                }

                ttls.forEach { ttl ->
                    val page = service(ttl).queryDeck(viewer, request(limit = 12)).items.map { UUID.fromString(it.userId) }
                    val positions = page.indices.filter { page[it] in boosted }
                    assertEquals(listOf(0, 4, 8), positions, "lifted Boosts must sit one in every four (ttl=$ttl): $page")
                }
            }
        }

        "boosted people who are close by anyway still get at most one card in every four" {
            runBlocking {
                seedLocals()
                // Six of the locals themselves are boosted.
                val boostedLocals = (0 until 6).map { UUID.fromString("00000000-0000-0000-0000-0000000b1%03d".format(it * 5)) }
                boostedLocals.forEach { dataSource.insertBoostActivation(it) }

                ttls.forEach { ttl ->
                    val page = service(ttl).queryDeck(viewer, request(limit = 12)).items.map { UUID.fromString(it.userId) }
                    val positions = page.indices.filter { page[it] in boostedLocals }
                    assertEquals(listOf(0, 4, 8), positions, "boosted cards must sit one in every four (ttl=$ttl): $page")
                }
            }
        }

        "a Boost never lifts someone outside the radius, filtered out, hidden, blocked or already swiped" {
            runBlocking {
                seedLocals()
                val outside = UUID.fromString("00000000-0000-0000-0000-0000000b4001")
                val tooOld = UUID.fromString("00000000-0000-0000-0000-0000000b4002")
                val swiped = UUID.fromString("00000000-0000-0000-0000-0000000b4003")
                val blocked = UUID.fromString("00000000-0000-0000-0000-0000000b4004")
                val hidden = UUID.fromString("00000000-0000-0000-0000-0000000b4005")
                val invisible = UUID.fromString("00000000-0000-0000-0000-0000000b4006")
                seedFar(outside, latitudeOffset = 0.40) // ~44 km: outside the 25 km step
                seedFar(tooOld, age = 55)
                seedFar(swiped)
                seedFar(blocked)
                seedFar(hidden)
                seedFar(invisible)
                listOf(outside, tooOld, swiped, blocked, hidden, invisible).forEach { dataSource.insertBoostActivation(it) }
                JdbcSwipeRepository(dataSource).recordSwipeAction(UUID.randomUUID(), viewer, swiped, SwipeAction.PASS)
                dataSource.exec("INSERT INTO user_blocks (id, blocker_user_id, blocked_user_id) VALUES (?, ?, ?)", UUID.randomUUID(), blocked, viewer)
                dataSource.exec("INSERT INTO user_hidden_profiles (id, hider_user_id, hidden_user_id) VALUES (?, ?, ?)", UUID.randomUUID(), viewer, hidden)
                dataSource.exec("UPDATE discovery_settings SET swipe_visible = false WHERE user_id = ?", invisible)

                ttls.forEach { ttl ->
                    val dealt = mutableListOf<String>()
                    var cursor: String? = null
                    var pages = 0
                    do {
                        val page = service(ttl).queryDeck(viewer, request(cursor = cursor, maximumAge = 40))
                        dealt += page.items.map { it.userId }
                        cursor = page.nextCursor
                        pages++
                    } while (cursor != null && pages < 10)
                    listOf(outside, tooOld, swiped, blocked, hidden, invisible).forEach { id ->
                        assertFalse(id.toString() in dealt, "$id was dealt (ttl=$ttl)")
                    }
                    assertEquals(30, dealt.size, "every local is still dealt (ttl=$ttl)")
                }
            }
        }

        "a boosted person dealt on the first page is not dealt again on the next one" {
            runBlocking {
                seedLocals()
                val boosted = UUID.fromString("00000000-0000-0000-0000-0000000b5001")
                seedFar(boosted)
                dataSource.insertBoostActivation(boosted)

                ttls.forEach { ttl ->
                    val deck = service(ttl)
                    val first = deck.queryDeck(viewer, request())
                    val second = deck.queryDeck(viewer, request(cursor = first.nextCursor))
                    assertTrue(boosted.toString() in first.items.map { it.userId })
                    assertFalse(boosted.toString() in second.items.map { it.userId }, "boosted person dealt twice (ttl=$ttl)")
                    assertEquals(20, (first.items + second.items).map { it.userId }.distinct().size)
                }
            }
        }
    })

private fun DataSource.exec(
    sql: String,
    vararg args: Any,
) {
    connection.use { c ->
        c.prepareStatement(sql).use { s ->
            args.forEachIndexed { i, arg -> s.setObject(i + 1, arg) }
            s.executeUpdate()
        }
    }
}
