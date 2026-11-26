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
import kotlin.test.assertFailsWith

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

        // Before: ORDER BY u.id (DISTINCT ON) despite the "soonest first" comment.
        "Visiting soon lists the soonest arrival first, then id" {
            runBlocking {
                val today = LocalDate.now(ZoneOffset.UTC)
                val viewer = sfViewer(dataSource)
                val late = nyMember(dataSource, "b01")
                val soon = nyMember(dataSource, "b02")
                val middle = nyMember(dataSource, "b03")
                val middleToo = nyMember(dataSource, "b04")
                dataSource.insertTrip(late, today.plusDays(7), today.plusDays(9), announce = true)
                dataSource.insertTrip(soon, today.plusDays(2), today.plusDays(3), announce = true)
                dataSource.insertTrip(middle, today.plusDays(4), today.plusDays(6), announce = true)
                dataSource.insertTrip(middleToo, today.plusDays(4), today.plusDays(5), announce = true)
                // A member with two trips here is listed once, at the sooner one.
                dataSource.insertTrip(late, today.plusDays(12), today.plusDays(13), announce = true)

                val result = GridService(JdbcGridRepository(dataSource)).queryGrid(viewer, visitingSoon())

                assertEquals(listOf(soon, middle, middleToo, late).map { it.toString() }, result.items.map { it.userId })
                assertEquals(today.plusDays(7).toString(), result.items.last().visitingFrom)
            }
        }

        // Before: every other filter was ignored.
        "Visiting soon applies the other Grid filters" {
            runBlocking {
                val today = LocalDate.now(ZoneOffset.UTC)
                val viewer = sfViewer(dataSource)
                val young = nyMember(dataSource, "c01", age = 25)
                val verified = nyMember(dataSource, "c02", age = 45)
                val unverified = nyMember(dataSource, "c03", age = 45)
                dataSource.markProfileVerified(verified)
                listOf(young, verified, unverified).forEach { dataSource.insertTrip(it, today.plusDays(3), today.plusDays(4), announce = true) }
                val grid = GridService(JdbcGridRepository(dataSource))

                assertEquals(
                    setOf(verified, unverified).map { it.toString() }.toSet(),
                    grid
                        .queryGrid(viewer, visitingSoon().copy(minimumAge = 40))
                        .items
                        .map { it.userId }
                        .toSet(),
                )
                assertEquals(
                    listOf(verified.toString()),
                    grid.queryGrid(viewer, visitingSoon().copy(minimumAge = 40, verifiedOnly = true)).items.map { it.userId },
                )
                // Linked: none of them has a partner.
                assertEquals(emptyList(), grid.queryGrid(viewer, visitingSoon().copy(linkedOnly = true)).items)
            }
        }

        "Visiting soon leaves out blocks, hides and people hiding from the viewer in SQL" {
            runBlocking {
                val today = LocalDate.now(ZoneOffset.UTC)
                val viewer = sfViewer(dataSource)
                val shown = nyMember(dataSource, "d01")
                val blocked = nyMember(dataSource, "d02")
                val blockedMe = nyMember(dataSource, "d03")
                val hidden = nyMember(dataSource, "d04")
                val hidingFromMe = nyMember(dataSource, "d05")
                listOf(shown, blocked, blockedMe, hidden, hidingFromMe).forEach {
                    dataSource.insertTrip(it, today.plusDays(1), today.plusDays(2), announce = true)
                }
                dataSource.insertBlock(viewer, blocked)
                dataSource.insertBlock(blockedMe, viewer)
                dataSource.insertHiddenProfile(viewer, hidden)

                // Repository level: no GridService backstop involved.
                val cards =
                    JdbcGridRepository(dataSource)
                        .queryVisitingSoon(viewer, 50_000, 60, hiddenFromViewerIds = setOf(hidingFromMe))
                        .cards
                assertEquals(listOf(shown), cards.map { it.userId })
            }
        }

        // Before: one page only, and no cursor.
        "Visiting soon pages with its own cursor" {
            runBlocking {
                val today = LocalDate.now(ZoneOffset.UTC)
                val viewer = sfViewer(dataSource)
                val members =
                    (1..15).map { n ->
                        nyMember(dataSource, "e" + n.toString().padStart(2, '0')).also {
                            dataSource.insertTrip(it, today.plusDays((n % 5).toLong()), today.plusDays(6), announce = true)
                        }
                    }
                val grid = GridService(JdbcGridRepository(dataSource))

                val first = grid.queryGrid(viewer, visitingSoon(limit = 12))
                assertEquals(12, first.items.size)
                val cursor = requireNotNull(first.nextCursor)
                val second = grid.queryGrid(viewer, visitingSoon(limit = 12).copy(cursor = cursor))
                assertEquals(3, second.items.size)
                assertEquals(null, second.nextCursor)
                val all = first.items + second.items
                assertEquals(members.map { it.toString() }.toSet(), all.map { it.userId }.toSet())
                assertEquals(all.size, all.map { it.userId }.distinct().size, "no repeats across pages")
                // Arrival order holds across the page break.
                val arrivals = all.map { LocalDate.parse(it.visitingFrom) }
                assertEquals(arrivals.sorted(), arrivals)

                // The cursor is sealed to Visiting soon: Nearby refuses it.
                val refused =
                    assertFailsWith<GridException> {
                        grid.queryGrid(viewer, GridQueryRequestDto(radiusMeters = 50_000, limit = 12, cursor = cursor))
                    }
                assertEquals("INVALID_GRID_CURSOR", refused.code)
            }
        }

        // Linked on Visiting soon uses the partner mini's gate (LinkedPartnerFilterSql), as on Nearby.
        "Visiting soon Linked keeps a shown partner and leaves out one hidden from the viewer" {
            runBlocking {
                val today = LocalDate.now(ZoneOffset.UTC)
                val viewer = sfViewer(dataSource)
                val shown = nyMember(dataSource, "f01")
                val viewerHid = nyMember(dataSource, "f02")
                val ownerHides = nyMember(dataSource, "f03")
                val hidingFromViewer = nyMember(dataSource, "f04")
                val single = nyMember(dataSource, "f05")
                val shownPartner = nyMember(dataSource, "f11")
                val hiddenByViewer = nyMember(dataSource, "f12")
                val ownerHiddenPartner = nyMember(dataSource, "f13")
                val contactBlockingPartner = nyMember(dataSource, "f14")
                dataSource.insertActiveProfileLink(shown, shownPartner)
                dataSource.insertActiveProfileLink(viewerHid, hiddenByViewer)
                dataSource.insertActiveProfileLink(ownerHides, ownerHiddenPartner)
                dataSource.insertActiveProfileLink(hidingFromViewer, contactBlockingPartner)
                dataSource.insertHiddenProfile(viewer, hiddenByViewer)
                dataSource.connection.use { connection ->
                    connection.prepareStatement("UPDATE discovery_settings SET partner_visibility = 'hidden' WHERE user_id = ?").use {
                        it.setObject(1, ownerHides)
                        it.executeUpdate()
                    }
                }
                listOf(shown, viewerHid, ownerHides, hidingFromViewer, single).forEach {
                    dataSource.insertTrip(it, today.plusDays(2), today.plusDays(3), announce = true)
                }
                val repository = JdbcGridRepository(dataSource)

                suspend fun visiting(linkedOnly: Boolean): Set<UUID> =
                    repository
                        .queryVisitingSoon(
                            viewer,
                            50_000,
                            60,
                            filters = com.pynapli.server.discovery.DiscoveryFilters(linkedOnly = linkedOnly),
                            // The contact-block set GridService passes: the partner hiding from the viewer.
                            hiddenFromViewerIds = setOf(contactBlockingPartner),
                        ).cards
                        .map { it.userId }
                        .toSet()

                assertEquals(setOf(shown), visiting(linkedOnly = true))
                assertEquals(setOf(viewerHid, ownerHides, hidingFromViewer, single), visiting(linkedOnly = false))
            }
        }
    })

private fun visitingSoon(limit: Int = 60) = GridQueryRequestDto(radiusMeters = 50_000, limit = limit, visitingSoonOnly = true)

// The viewer lives in San Francisco; every member lives in New York, so only a trip brings them here.
private fun sfViewer(dataSource: DataSource): UUID {
    val viewer = UUID.fromString("00000000-0000-0000-0000-000000000a01")
    dataSource.insertDiscoverableUser(viewer, "Viewer", age = 35, longitude = -122.4194, latitude = 37.7749)
    return viewer
}

private fun nyMember(
    dataSource: DataSource,
    suffix: String,
    age: Int = 33,
): UUID {
    val id = UUID.fromString("00000000-0000-0000-0000-000000000$suffix")
    dataSource.insertDiscoverableUser(id, "NY $suffix", age = age, longitude = -74.0060, latitude = 40.7128)
    return id
}

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
