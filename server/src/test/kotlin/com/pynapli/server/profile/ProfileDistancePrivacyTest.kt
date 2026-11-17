package com.pynapli.server.profile

import com.pynapli.server.grid.insertActiveTravelLocation
import com.pynapli.server.grid.insertDiscoverableUser
import com.pynapli.server.grid.moveUser
import com.pynapli.server.grid.setShowDistance
import com.pynapli.server.testsupport.PynapliTestDatabase
import io.kotest.core.spec.style.StringSpec
import kotlinx.coroutines.runBlocking
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * L-12: the profile peek is reachable for any user id, so it is the easiest distance oracle. Its
 * distance must follow the effective-location rule on BOTH sides (travel point when travelling),
 * be cell-to-cell, and be absent when the owner hides distance.
 */
class ProfileDistancePrivacyTest :
    StringSpec({
        val dataSource = PynapliTestDatabase.dataSource
        val repository = JdbcProfileRepository(dataSource)
        val viewer = UUID.fromString("00000000-0000-0000-0000-00000000d001")
        val target = UUID.fromString("00000000-0000-0000-0000-00000000d002")

        beforeTest {
            PynapliTestDatabase.reset()
            dataSource.insertDiscoverableUser(viewer, "Viewer", age = 30, longitude = -122.08, latitude = 37.42)
            // ~880 m east of the viewer.
            dataSource.insertDiscoverableUser(target, "Target", age = 30, longitude = -122.07, latitude = 37.42)
        }

        "a travelling owner's home distance never appears" {
            runBlocking {
                dataSource.insertActiveTravelLocation(target, "Elsewhere", latitude = 37.80, longitude = -122.27)
                val distance = assertNotNull(assertNotNull(repository.getPublicProfile(viewer, target)).distanceMeters)
                assertTrue(distance > 40_000, "home distance leaked: $distance")
            }
        }

        "a travelling viewer sees distance from their travel point, not their home" {
            runBlocking {
                dataSource.insertActiveTravelLocation(viewer, "Elsewhere", latitude = 37.80, longitude = -122.27)
                val distance = assertNotNull(assertNotNull(repository.getPublicProfile(viewer, target)).distanceMeters)
                assertTrue(distance > 40_000, "viewer home used: $distance")
            }
        }

        "an owner who hides distance returns no distance" {
            runBlocking {
                dataSource.setShowDistance(target, false)
                assertNull(assertNotNull(repository.getPublicProfile(viewer, target)).distanceMeters)
            }
        }

        "the distance is cell to cell: 30 m attacker steps and a 300 m target move inside a cell change nothing" {
            runBlocking {
                val readings =
                    (0..4).map { step ->
                        dataSource.moveUser(viewer, longitude = -122.08 + step * 0.00034, latitude = 37.42 + step * 0.00027)
                        assertNotNull(repository.getPublicProfile(viewer, target)).distanceMeters
                    } +
                        listOf(37.4170, 37.4199, 37.4230).map { lat ->
                            dataSource.moveUser(target, longitude = -122.07, latitude = lat)
                            assertNotNull(repository.getPublicProfile(viewer, target)).distanceMeters
                        }
                assertEquals(1, readings.toSet().size, "readings moved: $readings")
            }
        }
    })
