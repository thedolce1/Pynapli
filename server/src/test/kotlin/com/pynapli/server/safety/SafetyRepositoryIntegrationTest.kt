package com.pynapli.server.safety

import com.pynapli.server.moderation.JdbcAdminReportRepository
import com.pynapli.server.moderation.JdbcAdminRiskRepository
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
    fun hiddenProfileIsOneWayAndCanBeRemoved() = runBlocking {
        val viewer = UUID.fromString("00000000-0000-0000-0000-000000000414")
        val target = UUID.fromString("00000000-0000-0000-0000-000000000415")
        val repository = JdbcHiddenProfileRepository(dataSource)
        dataSource.insertUser(viewer)
        dataSource.insertUser(target)

        repository.hideProfile(viewer, target)

        assertEquals(setOf(target), repository.listHiddenProfileIds(viewer))
        assertEquals(emptySet(), repository.listHiddenProfileIds(target))

        val removed = repository.unhideProfile(viewer, target)
        assertTrue(removed)
        assertEquals(emptySet(), repository.listHiddenProfileIds(viewer))
    }

    @Test
    fun listHiddenProfilesJoinsProfileDisplayName() = runBlocking {
        val viewer = UUID.fromString("00000000-0000-0000-0000-000000000416")
        val target = UUID.fromString("00000000-0000-0000-0000-000000000417")
        val repository = JdbcHiddenProfileRepository(dataSource)
        dataSource.insertUser(viewer)
        dataSource.insertUser(target)
        dataSource.setProfileDisplayName(target, "Riley")

        repository.hideProfile(viewer, target)

        val summary = repository.listHiddenProfiles(viewer).single()
        assertEquals(target, summary.hiddenUserId)
        assertEquals("Riley", summary.hiddenDisplayName)
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

    @Test
    fun adminReportReviewFlowUpdatesStatusAndWritesAuditRows() = runBlocking {
        val reporter = UUID.fromString("00000000-0000-0000-0000-000000000441")
        val reported = UUID.fromString("00000000-0000-0000-0000-000000000442")
        val reportRepository = JdbcReportRepository(dataSource)
        val adminRepository = JdbcAdminReportRepository(dataSource)
        dataSource.insertUser(reporter)
        dataSource.insertUser(reported)
        val report = reportRepository.createReport(
            reporterUserId = reporter,
            reportedUserId = reported,
            targetType = ReportTargetType.PROFILE,
            targetId = null,
            category = ReportCategory.SPAM_OR_FAKE_PROFILE,
            note = "Looks fake"
        )

        val queue = adminRepository.listReports(
            status = "open",
            limit = 10,
            actorId = "moderator-1",
            reason = "daily report queue"
        )
        assertEquals(listOf(report.id), queue.map { it.reportId })

        val underReview = adminRepository.updateReportStatus(
            reportId = report.id,
            status = "under_review",
            actorId = "moderator-1",
            reason = "triaged"
        )
        assertEquals("under_review", underReview?.status)
        assertEquals("under_review", dataSource.reportStatus(report.id))

        val resolved = adminRepository.updateReportStatus(
            reportId = report.id,
            status = "resolved",
            actorId = "moderator-1",
            reason = "removed offending content"
        )

        assertEquals("resolved", resolved?.status)
        assertEquals("resolved", dataSource.reportStatus(report.id))
        assertEquals(1, dataSource.countAdminAuditRows("reports.list"))
        assertEquals(1, dataSource.countAdminAuditRows("report.under_review"))
        assertEquals(1, dataSource.countAdminAuditRows("report.resolved"))
        assertTrue(dataSource.reportReviewedAtExists(report.id))
    }

    @Test
    fun adminReportReviewDoesNotReopenResolvedReports() = runBlocking {
        val reporter = UUID.fromString("00000000-0000-0000-0000-000000000451")
        val reported = UUID.fromString("00000000-0000-0000-0000-000000000452")
        val reportRepository = JdbcReportRepository(dataSource)
        val adminRepository = JdbcAdminReportRepository(dataSource)
        dataSource.insertUser(reporter)
        dataSource.insertUser(reported)
        val report = reportRepository.createReport(
            reporterUserId = reporter,
            reportedUserId = reported,
            targetType = ReportTargetType.PROFILE,
            targetId = null,
            category = ReportCategory.OTHER,
            note = null
        )

        assertEquals(
            "dismissed",
            adminRepository.updateReportStatus(
                reportId = report.id,
                status = "dismissed",
                actorId = "moderator-1",
                reason = "not actionable"
            )?.status
        )

        val secondAction = adminRepository.updateReportStatus(
            reportId = report.id,
            status = "under_review",
            actorId = "moderator-1",
            reason = "late triage"
        )

        assertEquals(null, secondAction)
        assertEquals("dismissed", dataSource.reportStatus(report.id))
    }

    @Test
    fun riskRepositoryRecordsEventAndUpdatesState() = runBlocking {
        val actor = UUID.fromString("00000000-0000-0000-0000-000000000431")
        val target = UUID.fromString("00000000-0000-0000-0000-000000000432")
        val repository = JdbcRiskRepository(dataSource)
        dataSource.insertUser(actor)
        dataSource.insertUser(target)

        val first = repository.recordEvent(
            userId = target,
            actorUserId = actor,
            source = RiskService.SOURCE_REPORT,
            reason = "report.${ReportCategory.HARASSMENT_OR_THREATS}",
            delta = 20,
            metadataJson = """{"reportId":"00000000-0000-0000-0000-000000000433"}"""
        )
        val second = repository.recordEvent(
            userId = target,
            actorUserId = actor,
            source = RiskService.SOURCE_REPORT,
            reason = "report.${ReportCategory.UNDERAGE_USER}",
            delta = 35,
            metadataJson = """{"reportId":"00000000-0000-0000-0000-000000000434"}"""
        )

        assertEquals("clean", first.state.tier)
        assertEquals(55, second.state.score)
        assertEquals("friction", second.state.tier)
        assertEquals("friction", second.event.tierAfter)
        assertEquals(55, repository.getState(target)?.score)
    }

    @Test
    fun adminRiskReviewCanListSetClearAndAuditRiskStates() = runBlocking {
        val reporter = UUID.fromString("00000000-0000-0000-0000-000000000461")
        val target = UUID.fromString("00000000-0000-0000-0000-000000000462")
        val riskRepository = JdbcRiskRepository(dataSource)
        val adminRepository = JdbcAdminRiskRepository(dataSource)
        dataSource.insertUser(reporter)
        dataSource.insertUser(target)
        dataSource.setProfileDisplayName(target, "Risky")
        riskRepository.recordEvent(
            userId = target,
            actorUserId = reporter,
            source = RiskService.SOURCE_REPORT,
            reason = "report.${ReportCategory.UNDERAGE_USER}",
            delta = 55,
            metadataJson = """{"reportId":"00000000-0000-0000-0000-000000000463"}"""
        )

        val queue = adminRepository.listRiskStates(
            tierFilter = "needs_review",
            limit = 10,
            actorId = "moderator-1",
            reason = "daily risk queue"
        )
        assertEquals(listOf(target), queue.map { it.userId })
        assertEquals("Risky", queue.single().displayName)
        val events = adminRepository.listRiskEvents(
            userId = target,
            limit = 10,
            actorId = "moderator-1",
            reason = "risk investigation"
        )
        assertEquals(listOf("report.${ReportCategory.UNDERAGE_USER}"), events.map { it.reason })

        val quarantined = adminRepository.setRiskTier(
            userId = target,
            tier = "quarantine",
            actorId = "moderator-1",
            reason = "confirmed account farm"
        )

        assertEquals("quarantine", quarantined?.tier)
        assertEquals(70, quarantined?.score)
        assertEquals("quarantine", riskRepository.getState(target)?.tier)

        val cleared = adminRepository.setRiskTier(
            userId = target,
            tier = "clean",
            actorId = "moderator-1",
            reason = "appeal accepted"
        )

        assertEquals("clean", cleared?.tier)
        assertEquals(0, cleared?.score)
        assertEquals(emptyList(), adminRepository.listRiskStates("needs_review", 10, "moderator-1", "confirm clear"))
        assertEquals(2, dataSource.countAdminAuditRows("risk_states.list"))
        assertEquals(1, dataSource.countAdminAuditRows("risk_events.list"))
        assertEquals(1, dataSource.countAdminAuditRows("risk.set_quarantine"))
        assertEquals(1, dataSource.countAdminAuditRows("risk.set_clean"))
        assertEquals(2, dataSource.countRiskEvents(target, source = "admin_review"))
    }

    @Test
    fun adminRiskReviewCanMarkExistingStateReviewed() = runBlocking {
        val target = UUID.fromString("00000000-0000-0000-0000-000000000472")
        val adminRepository = JdbcAdminRiskRepository(dataSource)
        dataSource.insertUser(target)

        val created = adminRepository.setRiskTier(
            userId = target,
            tier = "watch",
            actorId = "moderator-1",
            reason = "manual watch"
        )
        val reviewed = adminRepository.markRiskReviewed(
            userId = target,
            actorId = "moderator-1",
            reason = "no immediate escalation"
        )

        assertEquals("watch", created?.tier)
        assertEquals("watch", reviewed?.tier)
        assertTrue(reviewed?.lastReviewedAt != null)
        assertEquals(1, dataSource.countAdminAuditRows("risk.reviewed"))
    }

    @Test
    fun adminRiskEnforcementLocksAccountRevokesSessionsAndAudits() = runBlocking {
        val target = UUID.fromString("00000000-0000-0000-0000-000000000482")
        val adminRepository = JdbcAdminRiskRepository(dataSource)
        dataSource.insertUser(target)
        dataSource.insertSession(
            sessionId = UUID.fromString("00000000-0000-0000-0000-000000000483"),
            userId = target
        )

        val locked = adminRepository.lockAccount(
            userId = target,
            actorId = "moderator-1",
            reason = "confirmed severe spam"
        )

        assertEquals("suspended", locked?.accountStatus)
        assertEquals("terminate", locked?.tier)
        assertEquals(90, locked?.score)
        assertEquals("suspended", dataSource.userStatus(target))
        assertEquals(1, dataSource.countRevokedSessions(target))
        assertEquals(1, dataSource.countAdminAuditRows("account.lock"))
        assertEquals(1, dataSource.countRiskEvents(target, source = "admin_enforcement"))
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

    private fun DataSource.insertSession(sessionId: UUID, userId: UUID) {
        connection.use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO user_sessions (
                    id,
                    user_id,
                    access_token_hash,
                    refresh_token_hash,
                    access_expires_at,
                    refresh_expires_at
                )
                VALUES (?, ?, ?, ?, now() + interval '15 minutes', now() + interval '30 days')
                """.trimIndent()
            ).use { statement ->
                statement.setObject(1, sessionId)
                statement.setObject(2, userId)
                statement.setString(3, "access-$sessionId")
                statement.setString(4, "refresh-$sessionId")
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

    private fun DataSource.reportStatus(reportId: UUID): String? =
        connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT status
                FROM safety_reports
                WHERE id = ?
                """.trimIndent()
            ).use { statement ->
                statement.setObject(1, reportId)
                statement.executeQuery().use { rs ->
                    if (rs.next()) rs.getString("status") else null
                }
            }
        }

    private fun DataSource.reportReviewedAtExists(reportId: UUID): Boolean =
        connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT reviewed_at IS NOT NULL AS reviewed
                FROM safety_reports
                WHERE id = ?
                """.trimIndent()
            ).use { statement ->
                statement.setObject(1, reportId)
                statement.executeQuery().use { rs ->
                    rs.next()
                    rs.getBoolean("reviewed")
                }
            }
        }

    private fun DataSource.countAdminAuditRows(action: String): Int =
        connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT COUNT(*) AS row_count
                FROM admin_audit_log
                WHERE action = ?
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, action)
                statement.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt("row_count")
                }
            }
        }

    private fun DataSource.countRiskEvents(userId: UUID, source: String): Int =
        connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT COUNT(*) AS row_count
                FROM account_risk_events
                WHERE user_id = ?
                  AND source = ?
                """.trimIndent()
            ).use { statement ->
                statement.setObject(1, userId)
                statement.setString(2, source)
                statement.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt("row_count")
                }
            }
        }

    private fun DataSource.userStatus(userId: UUID): String? =
        connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT status
                FROM users
                WHERE id = ?
                """.trimIndent()
            ).use { statement ->
                statement.setObject(1, userId)
                statement.executeQuery().use { rs ->
                    if (rs.next()) rs.getString("status") else null
                }
            }
        }

    private fun DataSource.countRevokedSessions(userId: UUID): Int =
        connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT COUNT(*) AS row_count
                FROM user_sessions
                WHERE user_id = ?
                  AND revoked_at IS NOT NULL
                """.trimIndent()
            ).use { statement ->
                statement.setObject(1, userId)
                statement.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt("row_count")
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
