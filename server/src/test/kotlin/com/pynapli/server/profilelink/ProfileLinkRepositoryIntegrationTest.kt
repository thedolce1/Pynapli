package com.pynapli.server.profilelink

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
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ProfileLinkRepositoryIntegrationTest {
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
                    poolName = "pynapli-profile-link-test"
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
    fun sameDirectionRetriedInvitationReplaysThePendingInvite() =
        runBlocking {
            val requester = UUID.fromString("00000000-0000-0000-0000-000000000901")
            val target = UUID.fromString("00000000-0000-0000-0000-000000000902")
            dataSource.insertUserWithProfile(requester, "Requester")
            dataSource.insertUserWithProfile(target, "Target")
            val repository = JdbcProfileLinkRepository(dataSource)

            val first = repository.createInvitation(requester, target)
            val retried = repository.createInvitation(requester, target)

            assertEquals(first.linkId, retried.linkId, "a retried invite must replay, not duplicate")
            assertEquals(1, dataSource.countPendingBetween(requester, target))
        }

    @Test
    fun reverseDirectionInviteRaceLeavesOnePendingRowAndMapsToInviteExists() =
        runBlocking {
            val userA = UUID.fromString("00000000-0000-0000-0000-000000000911")
            val userB = UUID.fromString("00000000-0000-0000-0000-000000000912")
            dataSource.insertUserWithProfile(userA, "Alex")
            dataSource.insertUserWithProfile(userB, "Blair")
            val repository = JdbcProfileLinkRepository(dataSource)
            val rival = JdbcProfileLinkRepository(dataSource)

            // Deterministically provoke the reverse race: the link-id factory runs after the
            // service-level findPendingBetween check would have passed and right before the
            // INSERT, so a reverse invite committed inside it collides with the V71
            // pending-pair index. The loser must map to PROFILE_LINK_INVITE_EXISTS, not a 500.
            val error =
                assertFailsWith<ProfileLinkException> {
                    repository.createInvitation(
                        requesterUserId = userA,
                        targetUserId = userB,
                        linkIdFactory = {
                            runBlocking { rival.createInvitation(userB, userA) }
                            UUID.randomUUID()
                        },
                    )
                }

            assertEquals("PROFILE_LINK_INVITE_EXISTS", error.code)
            assertEquals(1, dataSource.countPendingBetween(userA, userB))
        }

    @Test
    fun pendingPairUniqueIndexExists() {
        assertTrue(dataSource.indexExists("idx_user_profile_links_pending_pair"))
    }

    private fun DataSource.insertUserWithProfile(
        userId: UUID,
        displayName: String,
    ) {
        connection.use { connection ->
            connection.prepareStatement("INSERT INTO users (id, status) VALUES (?, 'active')").use { statement ->
                statement.setObject(1, userId)
                statement.executeUpdate()
            }
            connection
                .prepareStatement(
                    """
                    INSERT INTO profiles (user_id, display_name)
                    VALUES (?, ?)
                    ON CONFLICT (user_id) DO UPDATE SET display_name = EXCLUDED.display_name
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, userId)
                    statement.setString(2, displayName)
                    statement.executeUpdate()
                }
        }
    }

    private fun DataSource.countPendingBetween(
        userIdA: UUID,
        userIdB: UUID,
    ): Int =
        connection.use { connection ->
            connection
                .prepareStatement(
                    """
                    SELECT count(*)
                    FROM user_profile_links
                    WHERE status = 'pending'
                      AND unlinked_at IS NULL
                      AND LEAST(user_id, linked_user_id) = LEAST(?::uuid, ?::uuid)
                      AND GREATEST(user_id, linked_user_id) = GREATEST(?::uuid, ?::uuid)
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, userIdA)
                    statement.setObject(2, userIdB)
                    statement.setObject(3, userIdA)
                    statement.setObject(4, userIdB)
                    statement.executeQuery().use { rs ->
                        rs.next()
                        rs.getInt(1)
                    }
                }
        }

    private fun DataSource.indexExists(indexName: String): Boolean =
        connection.use { connection ->
            connection.prepareStatement("SELECT to_regclass(?) IS NOT NULL").use { statement ->
                statement.setString(1, indexName)
                statement.executeQuery().use { rs ->
                    rs.next()
                    rs.getBoolean(1)
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
                withDatabaseName("pynapli_profile_link_test")
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
