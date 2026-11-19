package com.pynapli.server.location

import com.pynapli.server.grid.insertDiscoverableUser
import com.pynapli.server.testsupport.PynapliTestDatabase
import com.pynapli.shared.location.LocationCell
import com.pynapli.shared.network.dto.UpdateCurrentLocationRequestDto
import io.kotest.core.spec.style.StringSpec
import kotlinx.coroutines.runBlocking
import java.util.UUID
import javax.sql.DataSource
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Location privacy: the service stores only a member's CELL, never the precise point.
 *
 *  - A location update stores exactly `pynapli_location_cell` of the input, with no accuracy.
 *  - The Kotlin twin [LocationCell] (used by every client before the fix leaves the device, and by
 *    [LocationService]) matches the SQL function bit for bit, ties and negatives included.
 *  - The V116 backfill puts every stored point on the grid and is a no-op the second time.
 */
class LocationCellStorageIntegrationTest :
    StringSpec({
        val dataSource = PynapliTestDatabase.dataSource

        beforeTest {
            PynapliTestDatabase.reset()
        }

        fun service() = LocationService(JdbcLocationRepository(dataSource))

        "a precise update stores exactly the cell centre, no accuracy, and a geohash that labels the cell" {
            runBlocking {
                val user = UUID.randomUUID()
                dataSource.insertDiscoverableUser(user, "Member", age = 30, longitude = 0.0, latitude = 0.0)

                val response =
                    service().updateCurrentLocation(
                        user,
                        UpdateCurrentLocationRequestDto(latitude = 45.501734, longitude = -73.567261, accuracyMeters = 18, source = "gps"),
                    )

                assertNull(response.accuracyMeters)
                val stored = assertNotNull(dataSource.storedLocation(user))
                assertEquals(4550, LocationCell.hundredths(stored.latitude))
                assertEquals(-7357, LocationCell.hundredths(stored.longitude))
                assertTrue(stored.latitude == dataSource.sqlCell(-73.567261, 45.501734).second, "stored ${stored.latitude}")
                assertTrue(stored.longitude == dataSource.sqlCell(-73.567261, 45.501734).first, "stored ${stored.longitude}")
                assertNull(stored.accuracyMeters)
                assertEquals("45.50:-73.57", stored.geohash)
            }
        }

        "two updates inside one cell store the identical point" {
            runBlocking {
                val user = UUID.randomUUID()
                dataSource.insertDiscoverableUser(user, "Member", age = 30, longitude = 0.0, latitude = 0.0)

                service().updateCurrentLocation(user, UpdateCurrentLocationRequestDto(latitude = 45.4951, longitude = -73.5749, source = "gps"))
                val first = assertNotNull(dataSource.storedLocation(user))
                service().updateCurrentLocation(user, UpdateCurrentLocationRequestDto(latitude = 45.5049, longitude = -73.5651, source = "gps"))
                val second = assertNotNull(dataSource.storedLocation(user))

                assertTrue(first.latitude == second.latitude && first.longitude == second.longitude, "$first vs $second")
            }
        }

        "the stored point equals pynapli_location_cell of the input for a spread of inputs" {
            runBlocking {
                val user = UUID.randomUUID()
                dataSource.insertDiscoverableUser(user, "Member", age = 30, longitude = 0.0, latitude = 0.0)

                samplePoints().take(60).forEach { (longitude, latitude) ->
                    service().updateCurrentLocation(user, UpdateCurrentLocationRequestDto(latitude = latitude, longitude = longitude, source = "network"))
                    val stored = assertNotNull(dataSource.storedLocation(user))
                    val (cellLongitude, cellLatitude) = dataSource.sqlCell(longitude, latitude)
                    assertTrue(
                        stored.longitude == cellLongitude && stored.latitude == cellLatitude,
                        "($longitude, $latitude) stored (${stored.longitude}, ${stored.latitude}), cell ($cellLongitude, $cellLatitude)",
                    )
                    assertNull(stored.accuracyMeters)
                }
            }
        }

        "the Kotlin LocationCell helper matches the SQL pynapli_location_cell exactly" {
            samplePoints().forEach { (longitude, latitude) ->
                val (sqlLongitude, sqlLatitude) = dataSource.sqlCell(longitude, latitude)
                val kotlinLongitude = LocationCell.snapCoordinate(longitude)
                val kotlinLatitude = LocationCell.snapCoordinate(latitude)
                assertEquals(LocationCell.hundredths(sqlLongitude), LocationCell.hundredths(longitude), "longitude $longitude")
                assertEquals(LocationCell.hundredths(sqlLatitude), LocationCell.hundredths(latitude), "latitude $latitude")
                // Bit-identical doubles (== also treats -0.0 and 0.0 alike, though both sides avoid -0.0).
                assertTrue(kotlinLongitude == sqlLongitude, "longitude $longitude: kotlin $kotlinLongitude sql $sqlLongitude")
                assertTrue(kotlinLatitude == sqlLatitude, "latitude $latitude: kotlin $kotlinLatitude sql $sqlLatitude")
            }
        }

        // V116 spelled the cell out as explicit arithmetic; on the PostGIS this suite runs (the one
        // dev and docker-compose pin) it must be exactly what V94's ST_SnapToGrid returned, so no
        // stored cell, distance or ordering moved when the function was replaced.
        "the pinned pynapli_location_cell equals ST_SnapToGrid(g, 0.01) on this PostGIS" {
            samplePoints().forEach { (longitude, latitude) ->
                val (cellLongitude, cellLatitude) = dataSource.sqlCell(longitude, latitude)
                val (gridLongitude, gridLatitude) = dataSource.snapToGrid(longitude, latitude)
                assertTrue(
                    cellLongitude == gridLongitude && cellLatitude == gridLatitude,
                    "($longitude, $latitude): cell ($cellLongitude, $cellLatitude) vs ST_SnapToGrid ($gridLongitude, $gridLatitude)",
                )
            }
        }

        "the V116 backfill puts every stored point on the grid and is a no-op the second time" {
            runBlocking {
                val users =
                    samplePoints().take(40).map { (longitude, latitude) ->
                        UUID.randomUUID().also { user ->
                            // Raw, pre-V116 shape: precise point, an accuracy figure, no geohash.
                            dataSource.insertDiscoverableUser(user, "Legacy", age = 30, longitude = longitude, latitude = latitude)
                        }
                    }
                // A legacy floored geohash must be replaced by the cell label.
                dataSource.connection.use { connection ->
                    connection
                        .prepareStatement("UPDATE user_locations SET geohash = 'legacy' WHERE user_id = ?")
                        .use { statement ->
                            statement.setObject(1, users.first())
                            statement.executeUpdate()
                        }
                }

                dataSource.runV116()
                val afterFirst = users.associateWith { assertNotNull(dataSource.storedLocation(it)) }
                afterFirst.values.forEach { stored ->
                    val (cellLongitude, cellLatitude) = dataSource.sqlCell(stored.longitude, stored.latitude)
                    assertTrue(stored.longitude == cellLongitude && stored.latitude == cellLatitude, "not on the grid: $stored")
                    assertNull(stored.accuracyMeters)
                    assertEquals(
                        "%s:%s".format(
                            java.math.BigDecimal(LocationCell.hundredths(stored.latitude)).movePointLeft(2).setScale(2).toPlainString(),
                            java.math.BigDecimal(LocationCell.hundredths(stored.longitude)).movePointLeft(2).setScale(2).toPlainString(),
                        ),
                        stored.geohash,
                    )
                }

                dataSource.runV116()
                val afterSecond = users.associateWith { assertNotNull(dataSource.storedLocation(it)) }
                // Same values, and the same row versions: the second run rewrote nothing.
                assertEquals(afterFirst, afterSecond)
            }
        }
    })

private data class StoredLocation(
    val longitude: Double,
    val latitude: Double,
    val accuracyMeters: Int?,
    val geohash: String?,
    val rowVersion: String,
)

private fun DataSource.storedLocation(userId: UUID): StoredLocation? =
    connection.use { connection ->
        connection
            .prepareStatement(
                """
                SELECT ST_X(geom::geometry) AS longitude, ST_Y(geom::geometry) AS latitude,
                       accuracy_meters, geohash, xmin::text AS row_version
                FROM user_locations
                WHERE user_id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, userId)
                statement.executeQuery().use { rs ->
                    if (!rs.next()) {
                        null
                    } else {
                        StoredLocation(
                            longitude = rs.getDouble("longitude"),
                            latitude = rs.getDouble("latitude"),
                            accuracyMeters = rs.getInt("accuracy_meters").takeUnless { rs.wasNull() },
                            geohash = rs.getString("geohash"),
                            rowVersion = rs.getString("row_version"),
                        )
                    }
                }
            }
    }

/** (longitude, latitude) of `pynapli_location_cell` of the point, straight from SQL. */
private fun DataSource.sqlCell(
    longitude: Double,
    latitude: Double,
): Pair<Double, Double> =
    connection.use { connection ->
        connection
            .prepareStatement(
                """
                SELECT ST_X(c::geometry) AS longitude, ST_Y(c::geometry) AS latitude
                FROM (SELECT pynapli_location_cell(ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography) AS c) cell
                """.trimIndent(),
            ).use { statement ->
                statement.setDouble(1, longitude)
                statement.setDouble(2, latitude)
                statement.executeQuery().use { rs ->
                    rs.next()
                    rs.getDouble("longitude") to rs.getDouble("latitude")
                }
            }
    }

/** (longitude, latitude) of PostGIS's own ST_SnapToGrid(g, 0.01), the V94 definition of a cell. */
private fun DataSource.snapToGrid(
    longitude: Double,
    latitude: Double,
): Pair<Double, Double> =
    connection.use { connection ->
        connection
            .prepareStatement(
                """
                SELECT ST_X(c::geometry) AS longitude, ST_Y(c::geometry) AS latitude
                FROM (
                    SELECT ST_SnapToGrid(ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography::geometry, 0.01)::geography AS c
                ) cell
                """.trimIndent(),
            ).use { statement ->
                statement.setDouble(1, longitude)
                statement.setDouble(2, latitude)
                statement.executeQuery().use { rs ->
                    rs.next()
                    rs.getDouble("longitude") to rs.getDouble("latitude")
                }
            }
    }

private fun DataSource.runV116() {
    val sql =
        assertNotNull(
            LocationCellStorageIntegrationTest::class.java.classLoader.getResource("db/migration/V116__store_only_location_cells.sql"),
        ).readText()
    connection.use { connection -> connection.createStatement().use { it.execute(sql) } }
}

/**
 * (longitude, latitude) inputs: half-way values (exact decimal ties like 2.305, which divide to
 * exactly 230.5, and near-ties either side), grid lines, zero from both sides, negatives on both
 * axes, high latitudes, and a seeded random spread.
 */
private fun samplePoints(): List<Pair<Double, Double>> {
    val fixed =
        listOf(
            2.305 to 48.905,
            -2.305 to -48.905,
            0.005 to 0.005,
            -0.005 to -0.005,
            0.015 to -0.015,
            0.0 to 0.0,
            -0.003 to -0.003,
            0.0049999 to 0.0050001,
            -0.0049999 to -0.0050001,
            -73.5673 to 45.5017,
            -122.0841 to 37.4220,
            -58.3849 to -34.6049,
            2.34 to 48.84,
            -58.38 to -34.60,
            1.005 to -1.005,
            12.345 to -12.345,
            0.125 to 0.135,
            151.2093 to -33.8688,
            179.985 to 89.985,
            -179.985 to -89.985,
        )
    val ties =
        (-179..179 step 7).map { k ->
            val longitude = k + 0.005
            val latitude = ((k % 89) + 0.015)
            longitude to latitude
        }
    val random = Random(20261119)
    val spread =
        List(200) {
            (random.nextDouble(-179.98, 179.98)) to (random.nextDouble(-89.98, 89.98))
        }
    return fixed + ties + spread
}
