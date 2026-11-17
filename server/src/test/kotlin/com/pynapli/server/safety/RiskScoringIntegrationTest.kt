package com.pynapli.server.safety

import com.pynapli.server.auth.JdbcAuthRepository
import com.pynapli.server.auth.security.PasswordHash
import com.pynapli.server.testsupport.PynapliTestDatabase
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Release-grade coverage of the authoritative abuse-scoring engine (F3 / TRUST_AND_SAFETY_SPEC).
 * The risk score math (accumulate deltas, clamp to [0,100], derive the tier thresholds) and the
 * score -> tier -> enforcement chain live in JdbcRiskRepository SQL + RiskPolicyService and were
 * only fake-tested before. These prove the real Postgres behavior end-to-end.
 */
class RiskScoringIntegrationTest {
    private val dataSource: DataSource = PynapliTestDatabase.dataSource

    @BeforeTest
    fun reset() {
        PynapliTestDatabase.reset()
    }

    @Test
    fun accumulatesDeltasAndDerivesTierThresholds() =
        runBlocking {
            val repository = JdbcRiskRepository(dataSource)
            val user = createUser()

            // First event: score = clamped delta, tier from the >=30 watch threshold.
            val watch = repository.recordEvent(user, null, "report", "report.spam", 35)
            assertEquals(35, watch.state.score)
            assertEquals("watch", watch.state.tier)
            assertEquals(35, watch.event.scoreAfter)
            assertEquals("watch", watch.event.tierAfter)

            // Accumulates to the >=70 quarantine threshold.
            val quarantine = repository.recordEvent(user, null, "report", "report.harassment", 35)
            assertEquals(70, quarantine.state.score)
            assertEquals("quarantine", quarantine.state.tier)

            // Ceiling clamps at 100 -> >=90 terminate.
            val terminate = repository.recordEvent(user, null, "report", "report.underage", 80)
            assertEquals(100, terminate.state.score)
            assertEquals("terminate", terminate.state.tier)
        }

    @Test
    fun floorsAtZeroAndNeverGoesNegative() =
        runBlocking {
            val repository = JdbcRiskRepository(dataSource)
            val user = createUser()

            // A lone negative delta on a fresh account floors at 0 / clean.
            val floored = repository.recordEvent(user, null, "verification", "verification.age_verified", -20)
            assertEquals(0, floored.state.score)
            assertEquals("clean", floored.state.tier)

            // A positive delta then an over-large negative nets to 0, not a negative score.
            repository.recordEvent(user, null, "report", "report.spam", 35)
            val netFloored = repository.recordEvent(user, null, "verification", "verification.age_verified", -50)
            assertEquals(0, netFloored.state.score)
            assertEquals("clean", netFloored.state.tier)
        }

    @Test
    fun tierBoundariesMapToTheRightTier() =
        runBlocking {
            val repository = JdbcRiskRepository(dataSource)
            // Each user crosses to exactly one threshold so the boundary mapping is unambiguous.
            assertEquals("clean", repository.recordEvent(createUser(), null, "report", "r", 29).state.tier)
            assertEquals("watch", repository.recordEvent(createUser(), null, "report", "r", 30).state.tier)
            assertEquals("friction", repository.recordEvent(createUser(), null, "report", "r", 50).state.tier)
            assertEquals("quarantine", repository.recordEvent(createUser(), null, "report", "r", 70).state.tier)
            assertEquals("terminate", repository.recordEvent(createUser(), null, "report", "r", 90).state.tier)
        }

    @Test
    fun riskEscalationDrivesEnforcementAndVerificationLiftsIt() =
        runBlocking {
            val riskService = RiskService(JdbcRiskRepository(dataSource))
            val policy = RiskPolicyService(riskService)
            val user = createUser()
            val reporter = createUser() // actor_user_id FKs users(id)

            // Below the quarantine threshold: not restricted.
            riskService.recordReportCreated(UUID.randomUUID(), reporter, user, "profile", "spam_or_fake_profile")
            assertEquals(false, policy.isSocialActionRestricted(user))
            assertEquals(false, policy.shouldHideFromDiscovery(user))

            // Accumulate report signals from several reporters across the quarantine threshold ->
            // restricted + hidden. (One reporter alone can't: ReportScoringPolicy, AUDIT-15.)
            repeat(3) {
                val another = createUser()
                riskService.recordReportCreated(UUID.randomUUID(), another, user, "profile", "harassment_or_threats")
            }
            assertEquals(true, policy.isSocialActionRestricted(user))
            assertEquals(true, policy.shouldHideFromDiscovery(user))

            // Successful age verification lowers the score back below quarantine -> enforcement lifts.
            repeat(3) { riskService.recordAgeVerificationPassed(user) }
            assertEquals(false, policy.isSocialActionRestricted(user))
        }

    private suspend fun createUser(): UUID {
        val userId = UUID.randomUUID()
        JdbcAuthRepository(dataSource).createEmailUser(
            userId = userId,
            identityId = UUID.randomUUID(),
            emailHash = "email-$userId",
            passwordHash = PasswordHash(hash = "hash", salt = "salt", iterations = 1),
            birthdate = LocalDate.parse("1990-05-13"),
            displayAge = 36,
            adultConfirmedAt = Instant.parse("2026-05-13T12:00:00Z"),
            termsAcceptedAt = Instant.parse("2026-05-13T12:00:00Z"),
            termsVersion = "2026-05-13",
            privacyAcceptedAt = Instant.parse("2026-05-13T12:00:00Z"),
            privacyVersion = "2026-05-13",
        )
        return userId
    }
}
