package com.pynapli.server.location

import com.pynapli.server.grid.insertActiveTravelLocation
import com.pynapli.server.grid.insertDiscoverableUser
import com.pynapli.server.testsupport.PynapliTestDatabase
import io.kotest.core.spec.style.StringSpec
import kotlinx.coroutines.runBlocking
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** L-13: the spoof-signal exemption reads the server's own Travel mode state, not a client claim. */
class JdbcLocationRepositoryTravelSessionTest :
    StringSpec({
        val dataSource = PynapliTestDatabase.dataSource

        beforeTest {
            PynapliTestDatabase.reset()
        }

        "stored coordinates report whether the server holds an active travel session" {
            runBlocking {
                val repository = JdbcLocationRepository(dataSource)
                val user = UUID.randomUUID()
                dataSource.insertDiscoverableUser(user, "Traveller", age = 30, longitude = -73.5673, latitude = 45.5017)

                assertEquals(false, assertNotNull(repository.getCurrentCoordinates(user)).travelSessionActive)
                dataSource.insertActiveTravelLocation(user, "London", latitude = 51.5074, longitude = -0.1278)
                assertEquals(true, assertNotNull(repository.getCurrentCoordinates(user)).travelSessionActive)
            }
        }
    })
