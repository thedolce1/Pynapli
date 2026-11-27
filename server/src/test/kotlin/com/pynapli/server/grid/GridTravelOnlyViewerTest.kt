package com.pynapli.server.grid

import com.pynapli.server.testsupport.PynapliTestDatabase
import com.pynapli.shared.network.dto.GridQueryRequestDto
import io.kotest.core.spec.style.StringSpec
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Pins what a Travel-mode viewer with NO current location gets from the grid (Section 1 review,
 * step 0 (ii)). The client's silent location refresh treats "the last live grid answer was not
 * LOCATION_REQUIRED" as "the server holds this account's current location", so a travel-only viewer
 * must still be answered LOCATION_REQUIRED, on the direct path that travel viewers always take (they
 * bypass the shared rank cache) as well as with the cache on. The viewer-location lookup reads
 * `user_locations` and only overlays an active travel point on top of it, so without a
 * `user_locations` row there is no viewer location at all.
 */
class GridTravelOnlyViewerTest :
    StringSpec({
        val dataSource = PynapliTestDatabase.dataSource

        beforeTest {
            PynapliTestDatabase.reset()
        }

        val viewer = UUID.fromString("00000000-0000-0000-0000-00000000b001")
        val nycLocal = UUID.fromString("00000000-0000-0000-0000-00000000b002")

        fun service(cacheTtl: Duration): GridService = GridService(JdbcGridRepository(dataSource, rankCacheTtl = cacheTtl))

        "a travel-only viewer (no user_locations row) is answered LOCATION_REQUIRED, cached or not" {
            runBlocking {
                dataSource.insertDiscoverableUser(viewer, "Viewer", age = 38, longitude = -122.4194, latitude = 37.7749)
                dataSource.deleteUserLocation(viewer)
                dataSource.insertActiveTravelLocation(userId = viewer, city = "New York", latitude = 40.7128, longitude = -74.0060)
                // Someone right at the travel point, so a travel-only answer would not be empty.
                dataSource.insertDiscoverableUser(nycLocal, "Maya", age = 34, longitude = -74.0060, latitude = 40.7128)

                for (ttl in listOf(Duration.ZERO, Duration.ofMinutes(5))) {
                    val error =
                        assertFailsWith<GridException>("cacheTtl=$ttl") {
                            service(ttl).queryGrid(viewer, GridQueryRequestDto(radiusMeters = 5_000, limit = 30))
                        }
                    assertEquals("LOCATION_REQUIRED", error.code, "cacheTtl=$ttl")
                }
            }
        }

        "control: the same viewer with a current location gets the travel city's cards" {
            runBlocking {
                dataSource.insertDiscoverableUser(viewer, "Viewer", age = 38, longitude = -122.4194, latitude = 37.7749)
                dataSource.insertActiveTravelLocation(userId = viewer, city = "New York", latitude = 40.7128, longitude = -74.0060)
                dataSource.insertDiscoverableUser(nycLocal, "Maya", age = 34, longitude = -74.0060, latitude = 40.7128)

                val answer = service(Duration.ZERO).queryGrid(viewer, GridQueryRequestDto(radiusMeters = 5_000, limit = 30))

                assertEquals(listOf(nycLocal.toString()), answer.items.map { it.userId })
            }
        }
    })

private fun DataSource.deleteUserLocation(userId: UUID) {
    connection.use { connection ->
        connection.prepareStatement("DELETE FROM user_locations WHERE user_id = ?").use { statement ->
            statement.setObject(1, userId)
            statement.executeUpdate()
        }
    }
}
