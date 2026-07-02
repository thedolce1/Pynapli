package com.pynapli.server.swipe

import com.pynapli.server.testsupport.PynapliTestDatabase
import io.kotest.core.spec.style.StringSpec
import kotlinx.coroutines.runBlocking
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SavedSwipeFilterRepositoryIntegrationTest :
    StringSpec({
        val dataSource = PynapliTestDatabase.dataSource

        beforeTest {
            PynapliTestDatabase.reset()
        }

        "create / list / delete round-trip" {
            runBlocking {
                val user = UUID.fromString("00000000-0000-0000-0000-000000000301")
                dataSource.insertActiveUser(user)
                val repository = JdbcSavedSwipeFilterRepository(dataSource)

                val created =
                    repository.create(
                        id = UUID.fromString("00000000-0000-0000-0000-000000000401"),
                        userId = user,
                        name = "Travel mode",
                        payloadJson = "{\"radiusMeters\":50000}",
                    )
                assertEquals("Travel mode", created.name)
                assertEquals(1, repository.countForUser(user))

                val list = repository.list(user)
                assertEquals(listOf(created.id), list.map { it.id })

                val removed = repository.delete(user, created.id)
                assertTrue(removed)
                assertEquals(0, repository.countForUser(user))
            }
        }

        "duplicate name (case-insensitive) raises SavedSwipeFilterDuplicateNameException" {
            runBlocking {
                val user = UUID.fromString("00000000-0000-0000-0000-000000000302")
                dataSource.insertActiveUser(user)
                val repository = JdbcSavedSwipeFilterRepository(dataSource)
                repository.create(
                    id = UUID.randomUUID(),
                    userId = user,
                    name = "Verified strict",
                    payloadJson = "{}",
                )

                assertFails {
                    repository.create(
                        id = UUID.randomUUID(),
                        userId = user,
                        name = "VERIFIED strict",
                        payloadJson = "{}",
                    )
                }.also { thrown ->
                    assertNotNull(thrown as? SavedSwipeFilterDuplicateNameException)
                }
            }
        }

        "delete returns false when row does not belong to user" {
            runBlocking {
                val owner = UUID.fromString("00000000-0000-0000-0000-000000000303")
                val intruder = UUID.fromString("00000000-0000-0000-0000-000000000304")
                dataSource.insertActiveUser(owner)
                dataSource.insertActiveUser(intruder)
                val repository = JdbcSavedSwipeFilterRepository(dataSource)
                val created =
                    repository.create(
                        id = UUID.randomUUID(),
                        userId = owner,
                        name = "Mine",
                        payloadJson = "{}",
                    )

                val removedByIntruder = repository.delete(intruder, created.id)
                assertEquals(false, removedByIntruder)
                assertEquals(1, repository.countForUser(owner))
            }
        }
    })

private fun DataSource.insertActiveUser(userId: UUID) {
    connection.use { connection ->
        connection.prepareStatement("INSERT INTO users (id, status) VALUES (?, 'active')").use { statement ->
            statement.setObject(1, userId)
            statement.executeUpdate()
        }
        connection
            .prepareStatement(
                "INSERT INTO profiles (user_id, moderation_status, visibility_status) VALUES (?, 'approved', 'visible')",
            ).use { statement ->
                statement.setObject(1, userId)
                statement.executeUpdate()
            }
    }
}
