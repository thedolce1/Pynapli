package com.pynapli.server.grid

import com.pynapli.server.presence.GridActiveAgo
import com.pynapli.server.presence.PresenceRepository
import com.pynapli.server.presence.PresenceService
import com.pynapli.server.presence.PresenceState
import com.pynapli.server.testsupport.PynapliTestDatabase
import com.pynapli.shared.network.PynapliJson
import com.pynapli.shared.network.dto.GridQueryRequestDto
import com.pynapli.shared.network.dto.GridQueryResponseDto
import io.kotest.core.spec.style.StringSpec
import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Plan G11: the card's coarse activeAgo key, server-computed, never a timestamp, nothing when hidden. */
class GridActiveAgoIntegrationTest :
    StringSpec({
        val dataSource = PynapliTestDatabase.dataSource

        beforeTest {
            PynapliTestDatabase.reset()
        }

        fun id(n: Int): UUID = UUID.fromString("00000000-0000-0000-0000-0000000aa%03d".format(n))

        "each card carries the coarse bucket of its owner's last activity, and online wins" {
            runBlocking {
                val now = Instant.now().truncatedTo(ChronoUnit.SECONDS)
                val viewer = id(0)
                val online = id(1)
                val tenMinutes = id(2)
                val fortyMinutes = id(3)
                val twoHours = id(4)
                val fiveHours = id(5)
                val threeDays = id(6)
                val nineDays = id(7)
                val ages =
                    mapOf(
                        online to Duration.ofDays(2),
                        tenMinutes to Duration.ofMinutes(10),
                        fortyMinutes to Duration.ofMinutes(40),
                        twoHours to Duration.ofHours(2),
                        fiveHours to Duration.ofHours(5),
                        threeDays to Duration.ofDays(3),
                        nineDays to Duration.ofDays(9),
                    )
                dataSource.insertDiscoverableUser(viewer, "Viewer", age = 30, longitude = -122.08, latitude = 37.42)
                ages.forEach { (userId, age) ->
                    dataSource.insertDiscoverableUser(userId, "U$userId".take(12), age = 30, longitude = -122.081, latitude = 37.421)
                    // Old accounts, so account creation does not count as recent activity.
                    dataSource.setUserCreatedDaysAgo(userId, days = 60)
                    dataSource.setLastActive(userId, now.minus(age))
                }
                val service =
                    GridService(
                        repository = JdbcGridRepository(dataSource),
                        presenceService = PresenceService(OnlineSet(setOf(online))),
                        clock = Clock.fixed(now, ZoneOffset.UTC),
                    )

                val response = service.queryGrid(viewer, GridQueryRequestDto(radiusMeters = 100_000))
                val byUser = response.items.associate { it.userId to it.activeAgo }

                assertEquals(GridActiveAgo.ONLINE, byUser[online.toString()], "online wins over a 2-day-old timestamp")
                assertEquals(GridActiveAgo.WITHIN_15_MINUTES, byUser[tenMinutes.toString()])
                assertEquals(GridActiveAgo.WITHIN_1_HOUR, byUser[fortyMinutes.toString()])
                assertEquals(GridActiveAgo.WITHIN_3_HOURS, byUser[twoHours.toString()])
                assertEquals(GridActiveAgo.TODAY, byUser[fiveHours.toString()])
                assertEquals(GridActiveAgo.THIS_WEEK, byUser[threeDays.toString()])
                assertNull(byUser[nineDays.toString()], "older than a week shows nothing")
                assertTrue(nineDays.toString() in byUser.keys, "the dormant card is still on the grid, just demoted")

                // Only keys leave the server: no timestamp anywhere in the card JSON.
                val json = PynapliJson.encodeToString(GridQueryResponseDto.serializer(), response)
                assertFalse(Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}""").containsMatchIn(json), json)
            }
        }

        "a member who hides their activity gets no key, while the online dot keeps today's rules" {
            runBlocking {
                val viewer = id(10)
                val hidden = id(11)
                dataSource.insertDiscoverableUser(viewer, "Viewer", age = 30, longitude = -122.08, latitude = 37.42)
                dataSource.insertDiscoverableUser(hidden, "Hidden", age = 30, longitude = -122.081, latitude = 37.421)
                dataSource.setLastActive(hidden, Instant.now().minus(Duration.ofMinutes(5)))
                dataSource.connection.use { connection ->
                    connection.prepareStatement("UPDATE discovery_settings SET incognito = true WHERE user_id = ?").use { statement ->
                        statement.setObject(1, hidden)
                        statement.executeUpdate()
                    }
                }

                // Incognito members never reach the Grid rank; the page hydration (shared with
                // Favorites) still fails safe if one ever does.
                val hydration = dataSource.connection.use { it.hydrateGridCards(viewer, listOf(hidden)) }.getValue(hidden)
                assertTrue(hydration.activityHidden)
                val card =
                    GridCardRecord(
                        userId = hidden,
                        displayName = "Hidden",
                        showDistance = false,
                        distanceMeters = 0.0,
                        lastActiveAt = hydration.lastActiveAt,
                        activityHidden = hydration.activityHidden,
                    ).toGridCardDto(onlineUserIds = setOf(hidden), mediaAssetUrlResolver = null)
                assertNull(card.activeAgo)
                assertTrue(card.online, "the online dot is unchanged for hidden members")
            }
        }

        "keys are coarse at every boundary" {
            fun key(since: Duration?) = GridActiveAgo.keyFor(online = false, activityHidden = false, sinceLastActive = since)
            assertEquals("15m", key(Duration.ofMinutes(15)))
            assertEquals("1h", key(Duration.ofMinutes(16)))
            assertEquals("1h", key(Duration.ofHours(1)))
            assertEquals("3h", key(Duration.ofMinutes(61)))
            assertEquals("today", key(Duration.ofHours(4)))
            assertEquals("week", key(Duration.ofHours(25)))
            assertEquals("week", key(Duration.ofDays(7)))
            assertNull(key(Duration.ofDays(8)))
            assertNull(key(null))
            assertNull(GridActiveAgo.keyFor(online = true, activityHidden = true, sinceLastActive = Duration.ZERO))
        }
    })

private class OnlineSet(
    private val online: Set<UUID>,
) : PresenceRepository {
    override suspend fun heartbeat(userId: UUID): PresenceState = PresenceState(online = true, expiresInSeconds = 900)

    override suspend fun onlineUserIds(userIds: Collection<UUID>): Set<UUID> = userIds.filterTo(mutableSetOf()) { it in online }
}
