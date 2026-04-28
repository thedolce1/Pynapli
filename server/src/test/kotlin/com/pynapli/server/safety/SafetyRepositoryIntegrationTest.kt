package com.pynapli.server.safety

import com.pynapli.shared.network.dto.ReportCategory
import com.pynapli.shared.network.dto.ReportTargetType
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

class SafetyRepositoryIntegrationTest {
    private lateinit var dataSource: HikariDataSource

    @Before
    fun setUp() {
        dataSource = HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = postgres.jdbcUrl
                username = postgres.username
                password = postgres.password
                maximumPoolSize = 2
                poolName = "pynapli-safety-test"
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
    fun blockIsBidirectionalAndCanBeRemoved() = runBlocking {
        val a = UUID.fromString("00000000-0000-0000-0000-000000000401")
        val b = UUID.fromString("00000000-0000-0000-0000-000000000402")
        val repository = JdbcBlockRepository(dataSource)
        dataSource.insertUser(a)
        dataSource.insertUser(b)

        repository.block(a, b, reason = "Spam")

        assertTrue(repository.isBlockedEitherWay(a, b))
        assertTrue(repository.isBlockedEitherWay(b, a))
        assertEquals(setOf(b), repository.listBlockedRelatedUserIds(a))
        assertEquals(setOf(a), repository.listBlockedRelatedUserIds(b))

        val removed = repository.unblock(a, b)
        assertTrue(removed)
        assertFalse(repository.isBlockedEitherWay(a, b))
    }

    @Test
    fun listBlocksJoinsProfileDisplayName() = runBlocking {
        val viewer = UUID.fromString("00000000-0000-0000-0000-000000000411")
        val targetWithName = UUID.fromString("00000000-0000-0000-0000-000000000412")
        val targetWithoutName = UUID.fromString("00000000-0000-0000-0000-000000000413")
        val repository = JdbcBlockRepository(dataSource)
        dataSource.insertUser(viewer)
        dataSource.insertUser(targetWithName)
        dataSource.insertUser(targetWithoutName)
        dataSource.setProfileDisplayName(targetWithName, "Maya")

        repository.block(viewer, targetWithoutName, reason = null)
        repository.block(viewer, targetWithName, reason = null)

        val summaries = repository.listBlocks(viewer)
        val byId = summaries.associateBy { it.blockedUserId }
        assertEquals("Maya", byId.getValue(targetWithName).blockedDisplayName)
        assertEquals(null, byId.getValue(targetWithoutName).blockedDisplayName)
    }

    @Test
    fun reportRepositoryStoresCategoriesAndNotes() = runBlocking {
        val reporter = UUID.fromString("00000000-0000-0000-0000-000000000421")
        val reported = UUID.fromString("00000000-0000-0000-0000-000000000422")
        val repository = JdbcReportRepository(dataSource)
        dataSource.insertUser(reporter)
        dataSource.insertUser(reported)

        val record = repository.createReport(
            reporterUserId = reporter,
            reportedUserId = reported,
            targetType = ReportTargetType.PROFILE,
            targetId = null,
            category = ReportCategory.HARASSMENT_OR_THREATS,
            note = "Repeated harassment"
        )

        assertEquals(ReportCategory.HARASSMENT_OR_THREATS, record.category)
        assertEquals("open", record.status)
        assertEquals("Repeated harassment", record.note)
    }

    private fun DataSource.insertUser(userId: UUID) {
        connection.use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO users (id, status)
                VALUES (?, 'active')
                """.trimIndent()
            ).use { statement ->
                statement.setObject(1, userId)
                statement.executeUpdate()
            }
        }
    }

    private fun DataSource.setProfileDisplayName(userId: UUID, displayName: String) {
        connection.use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO profiles (user_id, display_name)
                VALUES (?, ?)
                ON CONFLICT (user_id) DO UPDATE SET display_name = EXCLUDED.display_name
                """.trimIndent()
            ).use { statement ->
                statement.setObject(1, userId)
                statement.setString(2, displayName)
                statement.executeUpdate()
            }
        }
    }

    companion object {
        private val postgisImage = DockerImageName
            .parse("postgis/postgis:16-3.4")
            .asCompatibleSubstituteFor("postgres")

        private val postgres = PostgreSQLContainer<Nothing>(postgisImage).apply {
            withDatabaseName("pynapli_safety_test")
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
