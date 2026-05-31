package com.pynapli.server.grid

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.kotest.core.spec.style.StringSpec
import kotlinx.coroutines.runBlocking
import org.flywaydb.core.Flyway
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SavedGridFilterRepositoryIntegrationTest :
    StringSpec({
        val postgisImage =
            DockerImageName
                .parse("postgis/postgis:16-3.4")
                .asCompatibleSubstituteFor("postgres")
        val postgres =
            PostgreSQLContainer<Nothing>(postgisImage).apply {
                withDatabaseName("pynapli_saved_grid_filter_test")
                withUsername("pynapli")
                withPassword("pynapli_test_password")
            }
        lateinit var dataSource: HikariDataSource

        beforeSpec { postgres.start() }
        afterSpec { postgres.stop() }

        beforeTest {
            dataSource =
                HikariDataSource(
                    HikariConfig().apply {
                        jdbcUrl = postgres.jdbcUrl
                        username = postgres.username
                        password = postgres.password
                        maximumPoolSize = 2
                        poolName = "pynapli-saved-grid-filter-test"
                    },
                )
            Flyway
                .configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .load()
                .also { flyway ->
                    flyway.clean()
                    flyway.migrate()
                }
        }

        afterTest { dataSource.close() }

        "create / list / delete round-trip" {
            runBlocking {
                val user = UUID.fromString("00000000-0000-0000-0000-000000000101")
                dataSource.insertActiveUser(user)
                val repository = JdbcSavedGridFilterRepository(dataSource)

                val created =
                    repository.create(
                        id = UUID.fromString("00000000-0000-0000-0000-000000000201"),
                        userId = user,
                        name = "Nearby now",
                        payloadJson = "{\"radiusMeters\":5000}",
                    )
                assertEquals("Nearby now", created.name)
                assertEquals(1, repository.countForUser(user))

                val list = repository.list(user)
                assertEquals(listOf(created.id), list.map { it.id })

                val removed = repository.delete(user, created.id)
                assertTrue(removed)
                assertEquals(0, repository.countForUser(user))
            }
        }

        "duplicate name (case-insensitive) raises SavedGridFilterDuplicateNameException" {
            runBlocking {
                val user = UUID.fromString("00000000-0000-0000-0000-000000000102")
                dataSource.insertActiveUser(user)
                val repository = JdbcSavedGridFilterRepository(dataSource)
                repository.create(
                    id = UUID.randomUUID(),
                    userId = user,
                    name = "Verified only",
                    payloadJson = "{}",
                )

                assertFails {
                    repository.create(
                        id = UUID.randomUUID(),
                        userId = user,
                        name = "verified ONLY",
                        payloadJson = "{}",
                    )
                }.also { thrown ->
                    assertNotNull(thrown as? SavedGridFilterDuplicateNameException)
                }
            }
        }

        "delete returns false when row does not belong to user" {
            runBlocking {
                val owner = UUID.fromString("00000000-0000-0000-0000-000000000103")
                val intruder = UUID.fromString("00000000-0000-0000-0000-000000000104")
                dataSource.insertActiveUser(owner)
                dataSource.insertActiveUser(intruder)
                val repository = JdbcSavedGridFilterRepository(dataSource)
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
