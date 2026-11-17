package com.pynapli.server.safety

import com.pynapli.server.auth.AuthService
import com.pynapli.server.auth.JdbcAuthRepository
import com.pynapli.server.auth.security.PasswordHasher
import com.pynapli.server.testsupport.PynapliTestDatabase
import com.pynapli.shared.network.dto.CreateReportRequestDto
import com.pynapli.shared.network.dto.ReportCategory
import com.pynapli.shared.network.dto.ReportTargetType
import kotlinx.coroutines.runBlocking
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * AUDIT-15: reports alone must not let one account (or a handful) push someone into quarantine or
 * termination. A reporter counts once per reason per window; quarantine needs two distinct reporters;
 * terminate needs a human moderator.
 */
class ReportRiskDedupeIntegrationTest {
    private val dataSource = PynapliTestDatabase.dataSource
    private val auth = AuthService(JdbcAuthRepository(dataSource), passwordHasher = PasswordHasher(iterations = 1_000))
    private val risk = RiskService(JdbcRiskRepository(dataSource))
    private val reports = ReportService(repository = JdbcReportRepository(dataSource), riskSignalPublisher = risk)

    @BeforeTest
    fun reset() {
        PynapliTestDatabase.reset()
    }

    private suspend fun user(email: String) = UUID.fromString(auth.signupEmail(email, "report-password-1", "1990-01-01", true, true, true, null, "android", "1.0").user.userId)

    private suspend fun report(
        reporter: UUID,
        target: UUID,
        category: String,
    ) = reports.submitReport(
        reporter,
        CreateReportRequestDto(reportedUserId = target.toString(), targetType = ReportTargetType.PROFILE, category = category),
    )

    private fun count(
        sql: String,
        vararg args: Any?,
    ): Int =
        dataSource.connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                args.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
                statement.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }

    @Test
    fun oneReporterFilingTheSameReasonThreeTimesCountsOnceButEveryReportIsKept() =
        runBlocking {
            val reporter = user("reporter@example.com")
            val target = user("target@example.com")

            repeat(3) {
                val accepted = report(reporter, target, ReportCategory.UNDERAGE_USER)
                assertEquals("open", accepted.status)
            }

            val state = risk.getState(target)!!
            assertEquals(35, state.score)
            assertEquals("watch", state.tier)
            assertEquals(3, count("SELECT count(*) FROM safety_reports WHERE reported_user_id = ?", target), "moderators still see every report")
        }

    @Test
    fun oneReporterCyclingThroughCategoriesCannotQuarantineOrTerminate() =
        runBlocking {
            val reporter = user("cycler@example.com")
            val target = user("victim@example.com")

            listOf(
                ReportCategory.UNDERAGE_USER,
                ReportCategory.NON_CONSENSUAL_CONTENT,
                ReportCategory.PRIVATE_INFO_DOXXING,
                ReportCategory.HARASSMENT_OR_THREATS,
                ReportCategory.IMPERSONATION,
            ).forEach { report(reporter, target, it) }

            val state = risk.getState(target)!!
            assertTrue(state.score < 70, "a single reporter stopped short of quarantine, got ${state.score}")
            assertTrue(state.tier !in setOf("quarantine", "terminate"), "tier was ${state.tier}")
        }

    @Test
    fun twoDistinctReportersCanQuarantineButReportsAloneNeverTerminate() =
        runBlocking {
            val target = user("reported@example.com")
            val reporters = (1..5).map { user("r$it@example.com") }

            report(reporters[0], target, ReportCategory.UNDERAGE_USER)
            report(reporters[0], target, ReportCategory.NON_CONSENSUAL_CONTENT)
            assertTrue(risk.getState(target)!!.score < 70)

            report(reporters[1], target, ReportCategory.UNDERAGE_USER)
            assertEquals("quarantine", risk.getState(target)!!.tier, "two distinct reporters may quarantine")

            reporters.forEach { reporter ->
                listOf(ReportCategory.UNDERAGE_USER, ReportCategory.PRIVATE_INFO_DOXXING, ReportCategory.HARASSMENT_OR_THREATS)
                    .forEach { report(reporter, target, it) }
            }
            val state = risk.getState(target)!!
            assertEquals("quarantine", state.tier, "terminate needs a moderator decision")
            assertTrue(state.score < 90)
        }

    @Test
    fun aRepeatReportCountsAgainOnceTheWindowHasPassed() =
        runBlocking {
            val reporter = user("patient@example.com")
            val target = user("subject@example.com")
            report(reporter, target, ReportCategory.SPAM_OR_FAKE_PROFILE)
            dataSource.connection.use { connection ->
                connection
                    .prepareStatement("UPDATE account_risk_events SET created_at = now() - interval '31 days' WHERE user_id = ?")
                    .use {
                        it.setObject(1, target)
                        it.executeUpdate()
                    }
            }

            report(reporter, target, ReportCategory.SPAM_OR_FAKE_PROFILE)

            assertEquals(30, risk.getState(target)!!.score)
        }

    @Test
    fun otherRiskSignalsStillReachTerminateOnTheirOwn() =
        runBlocking {
            val target = user("flagged@example.com")
            risk.recordSevereMediaModerationFlag(UUID.randomUUID(), target, UUID.randomUUID(), "public", "csam_suspected", "underage_user")
            risk.recordImpossibleTravel(target, distanceKm = 5_000.0, elapsedSeconds = 600, speedKmh = 30_000.0)

            assertEquals("terminate", risk.getState(target)!!.tier)
        }
}
