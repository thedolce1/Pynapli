package com.pynapli.server.grid

import com.pynapli.server.discovery.DiscoveryFilters
import com.pynapli.server.swipe.JdbcSwipeRepository
import com.pynapli.server.swipe.listLinkedCandidateUserIdsNear
import com.pynapli.server.testsupport.PynapliTestDatabase
import io.kotest.core.spec.style.StringSpec
import kotlinx.coroutines.runBlocking
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.assertEquals

/**
 * Pins the linked-only Swipe deck for a mixed population, from two viewer positions at every radius
 * step that separates them. Membership is the candidate's EFFECTIVE location's cell (travel point
 * when travelling, else home) within the radius step of the viewer's cell; the linked partner must
 * be eligible and not blocked with the viewer.
 *
 * Everyone sits on longitude -122.08 (one cell column) at whole-cell latitudes, so every distance is
 * a whole number of 0.01-degree cells (~1.11 km each) and none is near a radius-step boundary.
 */
class SwipeLinkedOnlyDeckTest :
    StringSpec({
        val dataSource = PynapliTestDatabase.dataSource

        beforeTest {
            PynapliTestDatabase.reset()
        }

        "linked-only deck membership follows effective location, radius step and partner eligibility" {
            runBlocking {
                fun id(suffix: Int): UUID = UUID.fromString("00000000-0000-0000-0000-0000000d%04x".format(suffix))

                val longitude = -122.0841
                // Viewer A's cell is 37.42; viewer B's cell is 37.62 (~22 km north).
                val viewerA = id(0x1001)
                val viewerB = id(0x1002)
                dataSource.insertDiscoverableUser(viewerA, "Viewer A", age = 38, longitude = longitude, latitude = 37.4220)
                dataSource.insertDiscoverableUser(viewerB, "Viewer B", age = 38, longitude = longitude, latitude = 37.6220)

                // Distances below are from A, then from B.
                val near1Km = id(0x1011) // 1.1 km / 21 km
                val near2Km = id(0x1012) // 2.2 km / 20 km
                val near5Km = id(0x1013) // 5.5 km / 16.6 km
                val near17Km = id(0x1014) // 17.8 km / 4.4 km
                val far = id(0x1015) // 111 km / 133 km
                val travellingAway = id(0x1016) // home 1.1 km / 21 km, travelling 111 km / 133 km
                val travellingHere = id(0x1017) // home 111 km / 133 km, travelling 2.2 km / 20 km
                val travellingHidden = id(0x1018) // as travellingHere at 1.1 km, but hidden while travelling
                val partnerQuarantined = id(0x1019) // 1.1 km / 21 km
                val partnerHidden = id(0x101a) // 1.1 km / 21 km
                val partnerBlockedByA = id(0x101b) // 1.1 km / 21 km
                val unlinked = id(0x101c) // 1.1 km / 21 km

                val linkedHomes =
                    listOf(
                        near1Km to 37.43,
                        near2Km to 37.44,
                        near5Km to 37.47,
                        near17Km to 37.58,
                        far to 36.42,
                        travellingAway to 37.43,
                        travellingHere to 36.42,
                        travellingHidden to 36.42,
                        partnerQuarantined to 37.43,
                        partnerHidden to 37.43,
                        partnerBlockedByA to 37.43,
                    )
                val partners = mutableMapOf<UUID, UUID>()
                linkedHomes.forEachIndexed { index, (userId, latitude) ->
                    dataSource.insertDiscoverableUser(userId, "Linked $index", age = 35, longitude = longitude, latitude = latitude)
                    // Partners live far from both viewers, so they are never candidates themselves.
                    val partner = id(0x1101 + index)
                    dataSource.insertDiscoverableUser(partner, "Partner $index", age = 36, longitude = longitude, latitude = 30.0)
                    dataSource.insertActiveProfileLink(userId = userId, linkedUserId = partner)
                    partners[userId] = partner
                }
                dataSource.insertDiscoverableUser(unlinked, "Single", age = 33, longitude = longitude, latitude = 37.43)

                dataSource.insertActiveTravelLocation(travellingAway, "Away", latitude = 36.42, longitude = longitude)
                dataSource.insertActiveTravelLocation(travellingHere, "Here", latitude = 37.44, longitude = longitude)
                dataSource.insertActiveTravelLocation(travellingHidden, "Here", latitude = 37.43, longitude = longitude)
                dataSource.setVisibleWhileTraveling(travellingHidden, false)
                dataSource.setRiskState(partners.getValue(partnerQuarantined), score = 75, tier = "quarantine")
                dataSource.hideProfileForLinkedOnlyTest(partners.getValue(partnerHidden))
                dataSource.insertBlock(blockerUserId = viewerA, blockedUserId = partners.getValue(partnerBlockedByA))

                val expectedForA =
                    mapOf(
                        2_000 to setOf(near1Km),
                        5_000 to setOf(near1Km, near2Km, travellingHere),
                        10_000 to setOf(near1Km, near2Km, travellingHere, near5Km),
                        25_000 to setOf(near1Km, near2Km, travellingHere, near5Km, near17Km),
                        100_000 to setOf(near1Km, near2Km, travellingHere, near5Km, near17Km),
                    )
                val expectedForB =
                    mapOf(
                        2_000 to emptySet<UUID>(),
                        5_000 to setOf(near17Km),
                        10_000 to setOf(near17Km),
                        25_000 to setOf(near17Km, near5Km, near2Km, near1Km, travellingHere, partnerBlockedByA),
                        100_000 to setOf(near17Km, near5Km, near2Km, near1Km, travellingHere, partnerBlockedByA),
                    )

                val repository = JdbcSwipeRepository(dataSource)
                for ((viewer, expectedByRadius) in listOf(viewerA to expectedForA, viewerB to expectedForB)) {
                    for ((radius, expected) in expectedByRadius) {
                        val candidateIds =
                            dataSource.connection.use { connection ->
                                connection.listLinkedCandidateUserIdsNear(viewer, radius)
                            }
                        assertEquals(expected, candidateIds, "linked candidates for $viewer at $radius m")

                        val deck =
                            repository
                                .queryDeck(
                                    viewerUserId = viewer,
                                    radiusMeters = radius,
                                    limit = 20,
                                    filters = DiscoveryFilters(linkedOnly = true),
                                ).cards
                        assertEquals(expected, deck.map { it.userId }.toSet(), "linked-only deck for $viewer at $radius m")
                    }
                }
            }
        }
    })

private fun DataSource.hideProfileForLinkedOnlyTest(userId: UUID) {
    connection.use { connection ->
        connection.prepareStatement("UPDATE profiles SET visibility_status = 'hidden' WHERE user_id = ?").use { statement ->
            statement.setObject(1, userId)
            statement.executeUpdate()
        }
    }
}
