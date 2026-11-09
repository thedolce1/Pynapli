package com.pynapli.server.admin

import com.pynapli.server.auth.JdbcAuthRepository
import com.pynapli.server.auth.security.PasswordHash
import com.pynapli.server.safety.JdbcRiskRepository
import com.pynapli.server.testsupport.PynapliTestDatabase
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class JdbcAdminUserLookupRepositoryIntegrationTest {
    private val dataSource: DataSource = PynapliTestDatabase.dataSource

    @BeforeTest
    fun reset() {
        PynapliTestDatabase.reset()
    }

    @Test
    fun aggregatesRiskRecentReasonsAndAuditAndDefaultsCountsToZero() =
        runBlocking {
            val repository = JdbcAdminUserLookupRepository(dataSource)
            val userId = createUser()

            // A risk event sets the state (35 -> watch) and a recent reason.
            JdbcRiskRepository(dataSource).recordEvent(
                userId = userId,
                actorUserId = null,
                source = "report",
                reason = "harassment_report",
                delta = 35,
                metadataJson = "{}",
            ) { UUID.randomUUID() }

            // The lookup audit row is both written and surfaced as a recent moderation action.
            repository.recordLookupAudit(actorId = "ops-1", userId = userId, reason = "investigating report")

            val facts = repository.moderationFacts(userId)

            assertEquals("watch", facts.riskTier)
            assertEquals(35, facts.riskScore)
            assertEquals(1, facts.recentRiskReasons.size)
            assertEquals("harassment_report", facts.recentRiskReasons.first().reason)
            assertEquals(1, facts.recentModerationActions.size)
            assertEquals("user_lookup", facts.recentModerationActions.first().action)
            // No reports/blocks/sessions/devices seeded -> the count queries run and return zero.
            assertEquals(0, facts.reportsAgainstCount)
            assertEquals(0, facts.reportsByCount)
            assertEquals(0, facts.blocksAgainstCount)
            assertEquals(0, facts.blocksByCount)
            assertEquals(0, facts.activeSessionCount)
            assertEquals(0, facts.activeDeviceCount)
        }

    @Test
    fun cleanUserHasNoRiskNoReasonsNoActions() =
        runBlocking {
            val repository = JdbcAdminUserLookupRepository(dataSource)
            val facts = repository.moderationFacts(createUser())

            assertEquals("clean", facts.riskTier)
            assertEquals(0, facts.riskScore)
            assertEquals(emptyList(), facts.recentRiskReasons)
            assertEquals(emptyList(), facts.recentModerationActions)
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
