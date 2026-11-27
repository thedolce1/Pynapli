package com.pynapli.server.grid

import com.pynapli.server.safety.JdbcBlockRepository
import com.pynapli.server.safety.JdbcHiddenProfileRepository
import com.pynapli.server.testsupport.PynapliTestDatabase
import com.pynapli.shared.network.dto.GridQueryRequestDto
import io.kotest.core.spec.style.StringSpec
import kotlinx.coroutines.runBlocking
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/**
 * The filter sheet's "Show N people" (plan G2-4): the count runs the Grid's own filters, ranking and
 * visibility, so under the same request it never counts anyone the Grid would not return.
 */
class GridCountIntegrationTest :
    StringSpec({
        val dataSource = PynapliTestDatabase.dataSource

        beforeTest {
            PynapliTestDatabase.reset()
        }

        fun service(trust: GridViewerTrustRepository? = null): GridService {
            val hidden = JdbcHiddenProfileRepository(dataSource)
            return GridService(
                repository = JdbcGridRepository(dataSource),
                blockRepository = JdbcBlockRepository(dataSource),
                hiddenProfileRepository = hidden,
                viewerTrustRepository = trust,
            )
        }

        "the count equals the people the Grid returns, under every visibility rule and filter" {
            runBlocking {
                val viewer = UUID.randomUUID()
                val people = (1..7).map { UUID.randomUUID() }
                dataSource.insertDiscoverableUser(viewer, "Viewer", age = 38, longitude = -122.0841, latitude = 37.4220)
                people.forEachIndexed { index, id ->
                    dataSource.insertDiscoverableUser(id, "P$index", age = 30 + index, longitude = -122.0836, latitude = 37.4226)
                }
                // Blocked one way and the other, hidden by the viewer: never counted, never shown.
                dataSource.insertBlock(blockerUserId = viewer, blockedUserId = people[0])
                dataSource.insertBlock(blockerUserId = people[1], blockedUserId = viewer)
                dataSource.insertHiddenProfile(hiderUserId = viewer, hiddenUserId = people[2])
                dataSource.setProfileLifestyleTags(people[3], listOf("soft_swap"))
                dataSource.setProfileLifestyleTags(people[4], listOf("soft_swap", "same_room"))
                val grid = service()

                listOf(
                    GridQueryRequestDto(radiusMeters = 100_000),
                    GridQueryRequestDto(radiusMeters = 100_000, lifestyleTags = listOf("soft_swap")),
                    GridQueryRequestDto(radiusMeters = 100_000, minimumAge = 34),
                    GridQueryRequestDto(radiusMeters = 100_000, linkedOnly = true),
                ).forEach { request ->
                    val shown = grid.queryGrid(viewer, request).items.map { it.userId }
                    val counted = grid.countGrid(viewer, request)
                    assertEquals(shown.size, counted.count, "$request")
                    assertFalse(counted.atLeast, "$request")
                }
                assertEquals(4, grid.countGrid(viewer, GridQueryRequestDto(radiusMeters = 100_000)).count)
                assertEquals(2, grid.countGrid(viewer, GridQueryRequestDto(radiusMeters = 100_000, lifestyleTags = listOf("soft_swap"))).count)
            }
        }

        "a hide is reflected at once, not after the cache expires" {
            runBlocking {
                val viewer = UUID.randomUUID()
                val target = UUID.randomUUID()
                dataSource.insertDiscoverableUser(viewer, "Viewer", age = 38, longitude = -122.0841, latitude = 37.4220)
                dataSource.insertDiscoverableUser(target, "Target", age = 33, longitude = -122.0836, latitude = 37.4226)
                val grid = service()
                val request = GridQueryRequestDto(radiusMeters = 100_000)

                assertEquals(1, grid.countGrid(viewer, request).count)
                dataSource.insertHiddenProfile(hiderUserId = viewer, hiddenUserId = target)
                assertEquals(0, grid.countGrid(viewer, request).count)
                assertEquals(0, grid.queryGrid(viewer, request).items.size)
            }
        }

        "lifestyle tags keep the age gate on the count too" {
            runBlocking {
                val viewer = UUID.randomUUID()
                dataSource.insertDiscoverableUser(viewer, "Viewer", age = 38, longitude = -122.0841, latitude = 37.4220)
                val unverified =
                    service(
                        trust =
                            object : GridViewerTrustRepository {
                                override suspend fun isAgeVerified(userId: UUID): Boolean = false
                            },
                    )
                val error =
                    assertFailsWith<GridException> {
                        unverified.countGrid(viewer, GridQueryRequestDto(lifestyleTags = listOf("soft_swap")))
                    }
                assertEquals("AGE_VERIFICATION_REQUIRED", error.code)
            }
        }
    })
