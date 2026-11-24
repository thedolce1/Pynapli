package com.pynapli.server.favorites

import com.pynapli.server.discovery.DiscoveryCursorCodec
import com.pynapli.server.grid.GridException
import com.pynapli.server.grid.insertBlock
import com.pynapli.server.grid.insertDiscoverableUser
import com.pynapli.server.grid.insertHiddenProfile
import com.pynapli.server.grid.moveUser
import com.pynapli.server.grid.setRiskState
import com.pynapli.server.presence.PresenceRepository
import com.pynapli.server.presence.PresenceService
import com.pynapli.server.presence.PresenceState
import com.pynapli.server.safety.JdbcBlockRepository
import com.pynapli.server.testsupport.PynapliTestDatabase
import kotlinx.coroutines.runBlocking
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Grid Favorites: private, idempotent, capped, the same minimal card as the Grid, and the same
 * location privacy (coarse cells, coarse buckets, order that never reveals a hidden distance).
 */
class FavoriteServiceIntegrationTest {
    private val dataSource: DataSource = PynapliTestDatabase.dataSource
    private val codec = DiscoveryCursorCodec("favorites-test-cursor-secret")
    private val presence = MutablePresenceRepository()

    @BeforeTest
    fun reset() {
        PynapliTestDatabase.reset()
        presence.online.clear()
    }

    private fun service() =
        FavoriteService(
            repository = JdbcFavoriteRepository(dataSource),
            cursorCodec = codec,
            presenceService = PresenceService(presence),
            blockRepository = JdbcBlockRepository(dataSource),
        )

    private fun user(
        name: String,
        longitude: Double = -122.08,
        latitude: Double = 37.42,
    ): UUID = UUID.randomUUID().also { dataSource.insertDiscoverableUser(it, name, age = 30, longitude = longitude, latitude = latitude) }

    @Test
    fun addAndRemoveAreIdempotentAndPrivate() =
        runBlocking {
            val viewer = user("Viewer")
            val target = user("Target", latitude = 37.45)
            val service = service()

            assertTrue(service.add(viewer, target.toString()).favorite)
            assertTrue(service.add(viewer, target.toString()).favorite)
            assertTrue(service.isFavorite(viewer, target))
            assertEquals(listOf(target.toString()), service.list(viewer, null, null).items.map { it.userId })
            // The other person is never told: no activity, notification or push is written.
            assertEquals(0, dataSource.count("SELECT count(*) FROM activity_events"))
            assertEquals(0, dataSource.count("SELECT count(*) FROM notification_outbox"))
            // Not reciprocal: the target's own list is empty.
            assertTrue(service.list(target, null, null).items.isEmpty())

            assertTrue(!service.remove(viewer, target.toString()).favorite)
            assertTrue(!service.remove(viewer, target.toString()).favorite)
            assertTrue(service.list(viewer, null, null).items.isEmpty())
        }

    @Test
    fun deletingAnAccountRemovesItsFavoritesBothWays() =
        runBlocking {
            val viewer = user("Viewer")
            val target = user("Target")
            val service = service()
            service.add(viewer, target.toString())
            service.add(target, viewer.toString())

            com.pynapli.server.account
                .JdbcAccountDeletionActions(
                    dataSource,
                    com.pynapli.server.contactblocks.ContactBlockTokens
                        .localDevelopment(),
                ).deleteAccount(target)

            assertEquals(0, dataSource.count("SELECT count(*) FROM favorite_profiles"))
        }

    @Test
    fun selfMissingAndBlockedTargetsAreRefused() =
        runBlocking {
            val viewer = user("Viewer")
            val blocker = user("Blocker")
            dataSource.insertBlock(blocker, viewer)
            val service = service()

            assertEquals("INVALID_FAVORITE", assertFailsWith<GridException> { service.add(viewer, viewer.toString()) }.code)
            assertEquals("PROFILE_NOT_FOUND", assertFailsWith<GridException> { service.add(viewer, UUID.randomUUID().toString()) }.code)
            assertEquals("PROFILE_NOT_FOUND", assertFailsWith<GridException> { service.add(viewer, "not-a-uuid") }.code)
            assertEquals("PROFILE_NOT_FOUND", assertFailsWith<GridException> { service.add(viewer, blocker.toString()) }.code)
        }

    @Test
    fun theCapStopsNewFavoritesButNotReAdds() =
        runBlocking {
            val viewer = user("Viewer")
            val first = user("First")
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        """
                        INSERT INTO users (id, status)
                        SELECT gen_random_uuid(), 'active' FROM generate_series(1, ${FavoriteService.MAX_FAVORITES - 1})
                        """.trimIndent(),
                    )
                }
                connection
                    .prepareStatement(
                        "INSERT INTO favorite_profiles (user_id, favorite_user_id) SELECT ?, id FROM users WHERE id NOT IN (?, ?)",
                    ).use { statement ->
                        statement.setObject(1, viewer)
                        statement.setObject(2, viewer)
                        statement.setObject(3, first)
                        statement.executeUpdate()
                    }
            }
            val service = service()
            service.add(viewer, first.toString())
            val extra = user("Extra")

            assertEquals("FAVORITES_LIMIT_REACHED", assertFailsWith<GridException> { service.add(viewer, extra.toString()) }.code)
            assertTrue(service.add(viewer, first.toString()).favorite)
        }

    @Test
    fun blockedHiddenIncognitoBannedDeletedAndQuarantinedFavoritesDropOut() =
        runBlocking {
            val viewer = user("Viewer")
            val visible = user("Visible")
            val blockedByViewer = user("BlockedByViewer")
            val blockedViewer = user("BlockedViewer")
            val hidden = user("Hidden")
            val incognito = user("Incognito")
            val banned = user("Banned")
            val deleted = user("Deleted")
            val quarantined = user("Quarantined")
            val gridHidden = user("GridHidden")
            val service = service()
            listOf(visible, blockedByViewer, blockedViewer, hidden, incognito, banned, deleted, quarantined, gridHidden)
                .forEach { service.add(viewer, it.toString()) }

            dataSource.insertBlock(viewer, blockedByViewer)
            dataSource.insertBlock(blockedViewer, viewer)
            dataSource.insertHiddenProfile(viewer, hidden)
            dataSource.exec("UPDATE discovery_settings SET incognito = true WHERE user_id = ?", incognito)
            dataSource.exec("UPDATE users SET status = 'banned' WHERE id = ?", banned)
            dataSource.exec("UPDATE users SET deleted_at = now() WHERE id = ?", deleted)
            dataSource.setRiskState(quarantined, score = 90, tier = "quarantine")
            dataSource.exec("UPDATE discovery_settings SET grid_visible = false WHERE user_id = ?", gridHidden)

            assertEquals(listOf(visible.toString()), service.list(viewer, null, null).items.map { it.userId })
        }

    @Test
    fun onlineFavoritesComeFirstThenCoarseDistance() =
        runBlocking {
            val viewer = user("Viewer")
            val near = user("Near", latitude = 37.43)
            val mid = user("Mid", latitude = 37.60)
            val farOnline = user("FarOnline", latitude = 38.50)
            val service = service()
            listOf(farOnline, mid, near).forEach { service.add(viewer, it.toString()) }
            presence.online += farOnline

            val cards = service.list(viewer, null, null).items
            assertEquals(listOf(farOnline, near, mid).map { it.toString() }, cards.map { it.userId })
            assertTrue(cards.first().online)
        }

    @Test
    fun aHiddenDistanceFavoriteShowsNoDistanceAndKeepsItsPlaceWhereverItIs() =
        runBlocking {
            val viewer = user("Viewer")
            val showers =
                listOf(37.43, 37.47, 37.55, 37.70, 38.00, 38.40).map { latitude -> user("Shows $latitude", latitude = latitude) }
            val hider = user("Hider", latitude = 37.43)
            dataSource.exec("UPDATE discovery_settings SET show_distance = false WHERE user_id = ?", hider)
            val service = service()
            (showers + hider).forEach { service.add(viewer, it.toString()) }

            fun positionAndCard() =
                runBlocking { service.list(viewer, null, null).items }.let { items ->
                    items.indexOfFirst { it.userId == hider.toString() } to items.first { it.userId == hider.toString() }
                }

            val (nearPosition, nearCard) = positionAndCard()
            assertNull(nearCard.distanceMeters)
            assertNull(nearCard.distanceLabel)
            dataSource.moveUser(hider, longitude = -122.08, latitude = 38.45)
            val (farPosition, farCard) = positionAndCard()
            assertNull(farCard.distanceMeters)
            assertEquals(nearPosition, farPosition, "A hidden distance must not move the card when the person moves")
        }

    @Test
    fun movingTheViewerWithinItsCellNeverChangesADisplayedDistance() =
        runBlocking {
            val viewer = user("Viewer", latitude = 37.42)
            val target = user("Target", latitude = 37.429369)
            val service = service()
            service.add(viewer, target.toString())

            val readings =
                (0..4).map { step ->
                    dataSource.moveUser(viewer, longitude = -122.08, latitude = 37.42 + step * 0.00027)
                    val card = assertNotNull(service.list(viewer, null, null).items.single())
                    card.distanceMeters to card.distanceLabel
                }
            assertEquals(1, readings.toSet().size, "Displayed distance must not move with 30 m viewer steps: $readings")
        }

    @Test
    fun pagesCoverEveryFavoriteOnceAndCursorsAreSealedToTheViewer() =
        runBlocking {
            val viewer = user("Viewer")
            val other = user("Other")
            val service = service()
            val targets = (1..30).map { index -> user("T$index", latitude = 37.42 + index * 0.01) }
            targets.forEach { service.add(viewer, it.toString()) }

            val first = service.list(viewer, null, 12)
            assertEquals(12, first.items.size)
            val cursor = assertNotNull(first.nextCursor)
            val second = service.list(viewer, cursor, 12)
            val third = service.list(viewer, assertNotNull(second.nextCursor), 12)
            assertNull(third.nextCursor)
            val seen = (first.items + second.items + third.items).map { it.userId }
            assertEquals(targets.map { it.toString() }.toSet(), seen.toSet())
            assertEquals(seen.size, seen.toSet().size)

            assertEquals("INVALID_GRID_CURSOR", assertFailsWith<GridException> { service.list(other, cursor, 12) }.code)
            assertEquals("INVALID_GRID_CURSOR", assertFailsWith<GridException> { service.list(viewer, cursor.dropLast(2) + "AA", 12) }.code)
        }

    private fun DataSource.exec(
        sql: String,
        userId: UUID,
    ) {
        connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setObject(1, userId)
                statement.executeUpdate()
            }
        }
    }

    private fun DataSource.count(sql: String): Int =
        connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }
}

private class MutablePresenceRepository : PresenceRepository {
    val online = mutableSetOf<UUID>()

    override suspend fun heartbeat(userId: UUID): PresenceState = PresenceState(online = true, expiresInSeconds = 300)

    override suspend fun onlineUserIds(userIds: Collection<UUID>): Set<UUID> = userIds.filterTo(mutableSetOf()) { it in online }
}
