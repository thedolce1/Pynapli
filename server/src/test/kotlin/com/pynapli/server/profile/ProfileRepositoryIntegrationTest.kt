package com.pynapli.server.profile

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
import kotlin.test.assertNull

class ProfileRepositoryIntegrationTest {
    private lateinit var dataSource: HikariDataSource

    @Before
    fun setUp() {
        dataSource =
            HikariDataSource(
                HikariConfig().apply {
                    jdbcUrl = postgres.jdbcUrl
                    username = postgres.username
                    password = postgres.password
                    maximumPoolSize = 2
                    poolName = "pynapli-profile-test"
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

    @After
    fun tearDown() {
        dataSource.close()
    }

    @Test
    fun updateDiscoverySettingsPersistsVisibilityAndDistanceToggles() =
        runBlocking {
            val userId = UUID.fromString("00000000-0000-0000-0000-000000000701")
            dataSource.insertUserWithProfile(userId, "Seb Dev")
            val repository = JdbcProfileRepository(dataSource)

            val updated =
                repository.updateDiscoverySettings(
                    userId = userId,
                    update =
                        DiscoverySettingsUpdate(
                            gridVisible = false,
                            swipeVisible = true,
                            showDistance = false,
                            showInViewedLists = false,
                            allowGridMessages = true,
                            gridMessagePolicy = "matches_only",
                            allowFlirts = false,
                            allowIntroNotes = false,
                            allowAlbumRequests = false,
                            visibleWhileTraveling = null,
                        ),
                )

            val settings = requireNotNull(updated).discoverySettings
            assertEquals(false, settings.gridVisible)
            assertEquals(true, settings.swipeVisible)
            assertEquals(false, settings.showDistance)
            assertEquals(false, settings.showInViewedLists)
            assertEquals(true, settings.allowGridMessages)
            assertEquals("matches_only", settings.gridMessagePolicy)
            assertEquals(false, settings.allowFlirts)
            assertEquals(false, settings.allowIntroNotes)
            assertEquals(false, settings.allowAlbumRequests)
        }

    @Test
    fun publicProfileHidesQuarantinedTarget() =
        runBlocking {
            val viewer = UUID.fromString("00000000-0000-0000-0000-000000000711")
            val target = UUID.fromString("00000000-0000-0000-0000-000000000712")
            dataSource.insertUserWithProfile(viewer, "Viewer")
            dataSource.insertUserWithProfile(target, "Spam")
            dataSource.setRiskState(target, score = 75, tier = "quarantine")
            val repository = JdbcProfileRepository(dataSource)

            val profile = repository.getPublicProfile(viewer, target)

            assertNull(profile)
        }

    @Test
    fun publicProfileHidesQuarantinedLinkedPartnerMini() =
        runBlocking {
            val viewer = UUID.fromString("00000000-0000-0000-0000-000000000721")
            val target = UUID.fromString("00000000-0000-0000-0000-000000000722")
            val partner = UUID.fromString("00000000-0000-0000-0000-000000000723")
            dataSource.insertUserWithProfile(viewer, "Viewer")
            dataSource.insertUserWithProfile(target, "Maya")
            dataSource.insertUserWithProfile(partner, "Spam")
            dataSource.insertActiveProfileLink(userId = target, linkedUserId = partner)
            dataSource.setRiskState(partner, score = 75, tier = "quarantine")
            val repository = JdbcProfileRepository(dataSource)

            val profile = repository.getPublicProfile(viewer, target)

            assertEquals(target, requireNotNull(profile).userId)
            assertNull(profile.linkedPartnerUserId)
        }

    private fun DataSource.insertUserWithProfile(
        userId: UUID,
        displayName: String,
    ) {
        connection.use { connection ->
            connection
                .prepareStatement(
                    """
                    INSERT INTO users (id, status)
                    VALUES (?, 'active')
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, userId)
                    statement.executeUpdate()
                }
            connection
                .prepareStatement(
                    """
                    INSERT INTO profiles (user_id, display_name, moderation_status, visibility_status)
                    VALUES (?, ?, 'approved', 'visible')
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, userId)
                    statement.setString(2, displayName)
                    statement.executeUpdate()
                }
            connection
                .prepareStatement(
                    """
                    INSERT INTO discovery_settings (user_id, grid_visible, swipe_visible, show_distance)
                    VALUES (?, true, false, true)
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, userId)
                    statement.executeUpdate()
                }
        }
    }

    private fun DataSource.insertActiveProfileLink(
        userId: UUID,
        linkedUserId: UUID,
    ) {
        connection.use { connection ->
            connection
                .prepareStatement(
                    """
                    INSERT INTO user_profile_links (
                        id,
                        user_id,
                        linked_user_id,
                        status,
                        accepted_at
                    )
                    VALUES (?, ?, ?, 'active', now())
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, UUID.randomUUID())
                    statement.setObject(2, userId)
                    statement.setObject(3, linkedUserId)
                    statement.executeUpdate()
                }
        }
    }

    private fun DataSource.setRiskState(
        userId: UUID,
        score: Int,
        tier: String,
    ) {
        connection.use { connection ->
            connection
                .prepareStatement(
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
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, userId)
                    statement.setInt(2, score)
                    statement.setString(3, tier)
                    statement.executeUpdate()
                }
        }
    }

    companion object {
        private val postgisImage =
            DockerImageName
                .parse("postgis/postgis:16-3.4")
                .asCompatibleSubstituteFor("postgres")

        private val postgres =
            PostgreSQLContainer<Nothing>(postgisImage).apply {
                withDatabaseName("pynapli_profile_test")
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
