package com.pynapli.server.activity

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.runBlocking
import org.flywaydb.core.Flyway
import org.junit.After
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ActivityRepositoryIntegrationTest {
    private lateinit var dataSource: HikariDataSource

    @Before
    fun setUp() {
        dataSource = HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = postgres.jdbcUrl
                username = postgres.username
                password = postgres.password
                maximumPoolSize = 2
                poolName = "pynapli-activity-test"
            }
        )

        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .cleanDisabled(false)
            .load()
            .also { flyway ->
                flyway.clean()
                flyway.migrate()
            }
    }

    @After
    fun tearDown() {
        dataSource.close()
    }

    @Test
    fun recordProfileViewWritesEligibleViewInSingleUpsert() = runBlocking {
        val viewer = UUID.fromString("00000000-0000-0000-0000-000000001001")
        val target = UUID.fromString("00000000-0000-0000-0000-000000001002")
        dataSource.insertUserWithDiscovery(viewer)
        dataSource.insertUserWithDiscovery(target)
        val repository = JdbcActivityRepository(dataSource)

        assertTrue(repository.recordProfileView(viewer, target))
        assertTrue(repository.recordProfileView(viewer, target))

        val event = dataSource.singleProfileViewEvent()
        assertEquals(target, event.recipientUserId)
        assertEquals(viewer, event.actorUserId)
        assertEquals(target, event.targetUserId)
        assertEquals(1, event.aggregateCount)
    }

    @Test
    fun recordProfileViewSuppressesIneligibleViewersAndTargets() = runBlocking {
        val target = UUID.fromString("00000000-0000-0000-0000-000000001101")
        val incognitoViewer = UUID.fromString("00000000-0000-0000-0000-000000001102")
        val hiddenFromViewedLists = UUID.fromString("00000000-0000-0000-0000-000000001103")
        val quarantinedViewer = UUID.fromString("00000000-0000-0000-0000-000000001104")
        val inactiveTarget = UUID.fromString("00000000-0000-0000-0000-000000001105")
        val activeViewer = UUID.fromString("00000000-0000-0000-0000-000000001106")
        dataSource.insertUserWithDiscovery(target)
        dataSource.insertUserWithDiscovery(incognitoViewer, incognito = true)
        dataSource.insertUserWithDiscovery(hiddenFromViewedLists, showInViewedLists = false)
        dataSource.insertUserWithDiscovery(quarantinedViewer)
        dataSource.insertUserWithDiscovery(inactiveTarget, status = "paused")
        dataSource.insertUserWithDiscovery(activeViewer)
        dataSource.setRiskState(quarantinedViewer, score = 75, tier = "quarantine")
        val repository = JdbcActivityRepository(dataSource)

        assertFalse(repository.recordProfileView(incognitoViewer, target))
        assertFalse(repository.recordProfileView(hiddenFromViewedLists, target))
        assertFalse(repository.recordProfileView(quarantinedViewer, target))
        assertFalse(repository.recordProfileView(activeViewer, inactiveTarget))

        assertEquals(0, dataSource.profileViewEventCount())
    }

    private fun DataSource.insertUserWithDiscovery(
        userId: UUID,
        status: String = "active",
        showInViewedLists: Boolean = true,
        incognito: Boolean = false
    ) {
        connection.use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO users (id, status)
                VALUES (?, ?)
                """.trimIndent()
            ).use { statement ->
                statement.setObject(1, userId)
                statement.setString(2, status)
                statement.executeUpdate()
            }
            connection.prepareStatement(
                """
                INSERT INTO discovery_settings (
                    user_id,
                    incognito,
                    show_in_viewed_lists
                )
                VALUES (?, ?, ?)
                """.trimIndent()
            ).use { statement ->
                statement.setObject(1, userId)
                statement.setBoolean(2, incognito)
                statement.setBoolean(3, showInViewedLists)
                statement.executeUpdate()
            }
        }
    }

    private fun DataSource.setRiskState(userId: UUID, score: Int, tier: String) {
        connection.use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO account_risk_states (
                    user_id,
                    score,
                    tier,
                    last_event_at,
                    updated_at
                )
                VALUES (?, ?, ?, now(), now())
                ON CONFLICT (user_id) DO UPDATE
                SET score = EXCLUDED.score,
                    tier = EXCLUDED.tier,
                    last_event_at = EXCLUDED.last_event_at,
                    updated_at = EXCLUDED.updated_at
                """.trimIndent()
            ).use { statement ->
                statement.setObject(1, userId)
                statement.setInt(2, score)
                statement.setString(3, tier)
                statement.executeUpdate()
            }
        }
    }

    private fun DataSource.singleProfileViewEvent(): ProfileViewEventRow =
        connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT recipient_user_id, actor_user_id, target_user_id, aggregate_count
                FROM activity_events
                WHERE event_type = 'profile_view'
                """.trimIndent()
            ).use { statement ->
                statement.executeQuery().use { rs ->
                    assertTrue(rs.next())
                    val row = ProfileViewEventRow(
                        recipientUserId = rs.getObject("recipient_user_id", UUID::class.java),
                        actorUserId = rs.getObject("actor_user_id", UUID::class.java),
                        targetUserId = rs.getObject("target_user_id", UUID::class.java),
                        aggregateCount = rs.getInt("aggregate_count")
                    )
                    assertFalse(rs.next())
                    row
                }
            }
        }

    private fun DataSource.profileViewEventCount(): Int =
        connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT count(*)
                FROM activity_events
                WHERE event_type = 'profile_view'
                """.trimIndent()
            ).use { statement ->
                statement.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }

    private data class ProfileViewEventRow(
        val recipientUserId: UUID,
        val actorUserId: UUID,
        val targetUserId: UUID,
        val aggregateCount: Int
    )

    companion object {
        private val postgisImage = DockerImageName
            .parse("postgis/postgis:16-3.4")
            .asCompatibleSubstituteFor("postgres")

        private val postgres = PostgreSQLContainer<Nothing>(postgisImage).apply {
            withDatabaseName("pynapli_activity_test")
            withUsername("pynapli")
            withPassword("pynapli_test_password")
        }

        @JvmStatic
        @BeforeClass
        fun startPostgres() {
            postgres.start()
        }

        @JvmStatic
        @AfterClass
        fun stopPostgres() {
            postgres.stop()
        }
    }
}
