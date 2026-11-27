package com.pynapli.server.grid

import com.pynapli.server.safety.JdbcBlockRepository
import com.pynapli.server.safety.JdbcHiddenProfileRepository
import com.pynapli.server.testsupport.PynapliTestDatabase
import com.pynapli.shared.network.dto.GridQueryRequestDto
import io.kotest.core.spec.style.StringSpec
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * G2-2 ticket 5: every visibility rule holds on the next query even while the shared rank window is
 * still alive (a 5-minute TTL here, far longer than production's 5 s). Blocks, hides and contact
 * blocks are applied after the window by GridService; owner visibility (incognito, grid_visible,
 * profile visibility, account status, risk tier) is re-read for a page served from the window.
 */
class GridRankCacheVisibilityTest :
    StringSpec({
        val dataSource = PynapliTestDatabase.dataSource

        beforeTest {
            PynapliTestDatabase.reset()
        }

        "a member who goes incognito, leaves the Grid, hides, is suspended or quarantined mid-cache-life is gone on the next query" {
            runBlocking {
                val viewer = UUID.fromString("00000000-0000-0000-0000-000000022001")
                val targets = (2..7).map { UUID.fromString("00000000-0000-0000-0000-00000002200$it") }
                dataSource.insertDiscoverableUser(viewer, "Viewer", age = 30, longitude = -122.0840, latitude = 37.4220)
                targets.forEachIndexed { index, id ->
                    dataSource.insertDiscoverableUser(id, "T$index", age = 30, longitude = -122.0840 + 0.001 * (index + 1), latitude = 37.4220)
                }
                val (incognito, offGrid, hiddenProfile, suspended, quarantined, untouched) = targets
                val repository = JdbcGridRepository(dataSource, rankCacheTtl = Duration.ofMinutes(5))

                val before = repository.queryNearby(viewer, radiusMeters = 100_000, limit = 12).cards.map { it.userId }
                assertEquals(targets.toSet(), before.toSet())

                dataSource.update("UPDATE discovery_settings SET incognito = true WHERE user_id = ?", incognito)
                dataSource.update("UPDATE discovery_settings SET grid_visible = false WHERE user_id = ?", offGrid)
                dataSource.update("UPDATE profiles SET visibility_status = 'hidden' WHERE user_id = ?", hiddenProfile)
                dataSource.update("UPDATE users SET status = 'suspended' WHERE id = ?", suspended)
                dataSource.setRiskState(quarantined, score = 90, tier = "quarantine")
                // Proof the next query is served from the still-live window: a newcomer isn't in it yet.
                val newcomer = UUID.fromString("00000000-0000-0000-0000-000000022009")
                dataSource.insertDiscoverableUser(newcomer, "New", age = 30, longitude = -122.0835, latitude = 37.4220)

                val after = repository.queryNearby(viewer, radiusMeters = 100_000, limit = 12).cards.map { it.userId }

                assertFalse(newcomer in after, "test setup: the second query must be a cache hit")
                assertEquals(listOf(untouched), after)
            }
        }

        "a block either way or a hide made mid-cache-life drops the card on the next query" {
            runBlocking {
                val viewer = UUID.fromString("00000000-0000-0000-0000-000000022101")
                val blocked = UUID.fromString("00000000-0000-0000-0000-000000022102")
                val blockedViewer = UUID.fromString("00000000-0000-0000-0000-000000022103")
                val hidden = UUID.fromString("00000000-0000-0000-0000-000000022104")
                val kept = UUID.fromString("00000000-0000-0000-0000-000000022105")
                dataSource.insertDiscoverableUser(viewer, "Viewer", age = 30, longitude = -122.0840, latitude = 37.4220)
                listOf(blocked, blockedViewer, hidden, kept).forEachIndexed { index, id ->
                    dataSource.insertDiscoverableUser(id, "T$index", age = 30, longitude = -122.0830 + 0.001 * index, latitude = 37.4220)
                }
                val service =
                    GridService(
                        repository = JdbcGridRepository(dataSource, rankCacheTtl = Duration.ofMinutes(5)),
                        blockRepository = JdbcBlockRepository(dataSource),
                        hiddenProfileRepository = JdbcHiddenProfileRepository(dataSource),
                    )
                val request = GridQueryRequestDto(radiusMeters = 100_000, limit = 60)
                assertEquals(setOf(blocked, blockedViewer, hidden, kept), service.ids(viewer, request).toSet())

                dataSource.insertBlock(blockerUserId = viewer, blockedUserId = blocked)
                dataSource.insertBlock(blockerUserId = blockedViewer, blockedUserId = viewer)
                dataSource.insertHiddenProfile(hiderUserId = viewer, hiddenUserId = hidden)

                assertEquals(listOf(kept), service.ids(viewer, request))
            }
        }

        "the next cursor anchors on the last ranked row, so a row dropped by the re-check skips nobody" {
            runBlocking {
                val viewer = UUID.fromString("00000000-0000-0000-0000-000000022201")
                val ids = (2..7).map { UUID.fromString("00000000-0000-0000-0000-00000002220$it") }
                dataSource.insertDiscoverableUser(viewer, "Viewer", age = 30, longitude = -122.0840, latitude = 37.4220)
                ids.forEachIndexed { index, id ->
                    dataSource.insertDiscoverableUser(id, "T$index", age = 30, longitude = -122.0840 + 0.01 * (index + 1), latitude = 37.4220)
                }
                val repository = JdbcGridRepository(dataSource, rankCacheTtl = Duration.ofMinutes(5))
                val ranked = repository.queryNearby(viewer, radiusMeters = 100_000, limit = 12).cards.map { it.userId }
                // Hide the last card of page one after the window was filled.
                dataSource.update("UPDATE discovery_settings SET incognito = true WHERE user_id = ?", ranked[2])

                val pageOne = repository.queryNearby(viewer, radiusMeters = 100_000, limit = 3)
                val pageTwo = repository.queryNearby(viewer, radiusMeters = 100_000, limit = 3, cursor = pageOne.nextCursorAnchor)

                assertEquals(ranked.take(2), pageOne.cards.map { it.userId })
                assertEquals(ranked.drop(3).take(3), pageTwo.cards.map { it.userId })
                assertTrue(pageOne.nextCursorAnchor != null)
            }
        }
    })

private suspend fun GridService.ids(
    viewer: UUID,
    request: GridQueryRequestDto,
): List<UUID> = queryGrid(viewer, request).items.map { UUID.fromString(it.userId) }

private fun DataSource.update(
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

private operator fun <T> List<T>.component6(): T = this[5]
