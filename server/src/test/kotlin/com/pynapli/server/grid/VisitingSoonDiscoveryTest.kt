package com.pynapli.server.grid

import com.pynapli.server.testsupport.PynapliTestDatabase
import com.pynapli.shared.network.dto.GridQueryRequestDto
import io.kotest.core.spec.style.StringSpec
import kotlinx.coroutines.runBlocking
import java.sql.Date
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.assertEquals

class VisitingSoonDiscoveryTest :
    StringSpec({
        val dataSource = PynapliTestDatabase.dataSource

        beforeTest {
            PynapliTestDatabase.reset()
        }

        "Visiting soon shows only announced trips arriving here within two weeks" {
            runBlocking {
                val today = LocalDate.now(ZoneOffset.UTC)
                // Viewer lives in San Francisco; everyone else lives in New York.
                val viewer = UUID.fromString("00000000-0000-0000-0000-000000000a01")
                dataSource.insertDiscoverableUser(viewer, "Viewer", age = 35, longitude = -122.4194, latitude = 37.7749)
                val announced = UUID.fromString("00000000-0000-0000-0000-000000000a02")
                val private = UUID.fromString("00000000-0000-0000-0000-000000000a03")
                val tooFarAhead = UUID.fromString("00000000-0000-0000-0000-000000000a04")
                val elsewhere = UUID.fromString("00000000-0000-0000-0000-000000000a05")
                listOf(announced, private, tooFarAhead, elsewhere).forEach {
                    dataSource.insertDiscoverableUser(it, "NY ${it.toString().takeLast(2)}", age = 33, longitude = -74.0060, latitude = 40.7128)
                }
                dataSource.insertTrip(announced, today.plusDays(5), today.plusDays(8), announce = true)
                dataSource.insertTrip(private, today.plusDays(5), today.plusDays(8), announce = false)
                dataSource.insertTrip(tooFarAhead, today.plusDays(30), today.plusDays(32), announce = true)
                dataSource.insertTrip(elsewhere, today.plusDays(3), today.plusDays(4), announce = true, latitude = 34.05, longitude = -118.24)

                val grid = GridService(JdbcGridRepository(dataSource))
                val result = grid.queryGrid(viewer, GridQueryRequestDto(radiusMeters = 50_000, limit = 60, visitingSoonOnly = true))

                assertEquals(listOf(announced.toString()), result.items.map { it.userId })
                assertEquals(today.plusDays(5).toString(), result.items.single().visitingFrom)
                assertEquals(today.plusDays(8).toString(), result.items.single().visitingUntil)
                assertEquals(null, result.items.single().distanceLabel, "they aren't here yet")
                assertEquals(null, result.nextCursor)
            }
        }
    })

private fun DataSource.insertTrip(
    userId: UUID,
    startsOn: LocalDate,
    endsOn: LocalDate,
    announce: Boolean,
    latitude: Double = 37.7749,
    longitude: Double = -122.4194,
) {
    connection.use { connection ->
        connection
            .prepareStatement(
                """
                INSERT INTO travel_trips (id, user_id, city, latitude, longitude, starts_on, ends_on, timezone, announce_to_locals)
                VALUES (?, ?, 'San Francisco', ?, ?, ?, ?, 'UTC', ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, UUID.randomUUID())
                statement.setObject(2, userId)
                statement.setDouble(3, latitude)
                statement.setDouble(4, longitude)
                statement.setDate(5, Date.valueOf(startsOn))
                statement.setDate(6, Date.valueOf(endsOn))
                statement.setBoolean(7, announce)
                statement.executeUpdate()
            }
    }
}
