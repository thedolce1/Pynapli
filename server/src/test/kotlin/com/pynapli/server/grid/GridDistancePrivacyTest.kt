package com.pynapli.server.grid

import com.pynapli.server.distance.toPublicDistanceMeters
import com.pynapli.server.testsupport.PynapliTestDatabase
import com.pynapli.shared.network.dto.GridQueryRequestDto
import io.kotest.core.spec.style.StringSpec
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * AUDIT-10: displayed distance is computed between coarse cells, not raw GPS points, so an
 * attacker shuffling their own reported location by tens of metres never sees another user's
 * reading change (no boundary-crossing trilateration).
 */
class GridDistancePrivacyTest :
    StringSpec({
        val dataSource = PynapliTestDatabase.dataSource

        beforeTest {
            PynapliTestDatabase.reset()
        }

        "moving the attacker 30 m at a time never changes another user's displayed distance" {
            runBlocking {
                val attacker = UUID.fromString("00000000-0000-0000-0000-000000010001")
                val target = UUID.fromString("00000000-0000-0000-0000-000000010002")
                // Attacker starts at a cell centre; target ~1,040 m due north (raw distance straddles
                // the old 1,000 m / 900 m boundary as the attacker walks north).
                dataSource.insertDiscoverableUser(attacker, "Attacker", age = 30, longitude = -122.08, latitude = 37.42)
                dataSource.insertDiscoverableUser(target, "Target", age = 30, longitude = -122.08, latitude = 37.429369)
                val service = GridService(JdbcGridRepository(dataSource, rankCacheTtl = Duration.ZERO))

                val readings =
                    (0..4).map { step ->
                        dataSource.moveUser(attacker, longitude = -122.08, latitude = 37.42 + step * 0.00027)
                        val card =
                            assertNotNull(
                                service
                                    .queryGrid(attacker, GridQueryRequestDto(radiusMeters = 5_000, limit = 20))
                                    .items
                                    .firstOrNull { it.userId == target.toString() },
                            )
                        card.distanceMeters to card.distanceLabel
                    }

                assertEquals(1, readings.toSet().size, "Displayed distance must not move with 30 m attacker steps: $readings")
            }
        }

        "SQL public distance buckets match the Kotlin buckets" {
            val samples = listOf(0.0, 999.0, 1_999.9, 2_000.0, 7_654.3, 9_999.9, 10_000.0, 24_999.0, 49_999.0, 50_000.0, 99_999.0)
            dataSource.connection.use { connection ->
                samples.forEach { d ->
                    connection.prepareStatement("SELECT pynapli_public_distance_m(?)").use { statement ->
                        statement.setDouble(1, d)
                        statement.executeQuery().use { rs ->
                            rs.next()
                            assertEquals(
                                d.toPublicDistanceMeters(),
                                rs.getInt(1),
                                "bucket for $d",
                            )
                        }
                    }
                }
            }
        }
    })

internal fun DataSource.moveUser(
    userId: UUID,
    longitude: Double,
    latitude: Double,
) {
    connection.use { connection ->
        connection
            .prepareStatement(
                "UPDATE user_locations SET geom = ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography WHERE user_id = ?",
            ).use { statement ->
                statement.setDouble(1, longitude)
                statement.setDouble(2, latitude)
                statement.setObject(3, userId)
                statement.executeUpdate()
            }
    }
}
