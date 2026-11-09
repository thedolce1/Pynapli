package com.pynapli.server.admin

import com.pynapli.server.auth.JdbcAuthRepository
import com.pynapli.server.auth.security.PasswordHash
import com.pynapli.server.testsupport.PynapliTestDatabase
import kotlinx.coroutines.runBlocking
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class JdbcAdminSubscriptionSupportRepositoryIntegrationTest {
    private val dataSource: DataSource = PynapliTestDatabase.dataSource

    @BeforeTest
    fun reset() {
        PynapliTestDatabase.reset()
    }

    @Test
    fun readsOwnedSubscriptionsAndGrantedReceivedBenefitsThenWritesAudit() =
        runBlocking {
            val repository = JdbcAdminSubscriptionSupportRepository(dataSource)
            val userId = createUser()
            val beneficiaryId = createUser()
            val benefactorId = createUser()

            dataSource.insertSubscription(
                userId = userId,
                platform = "play_store",
                externalSubscriptionId = "token-$userId",
                tier = "select",
                status = "active",
                periodStart = Instant.parse("2026-06-01T00:00:00Z"),
                periodEnd = Instant.parse("2026-07-01T00:00:00Z"),
                externalProductId = "pynapli_select_monthly",
                externalStatus = "SUBSCRIPTION_STATE_ACTIVE",
                acknowledgementState = "ACKNOWLEDGED",
                lastVerifiedAt = Instant.parse("2026-06-15T00:00:00Z"),
            )
            // This user grants a benefit to beneficiaryId (they are the subscriber).
            dataSource.insertBenefit(
                subscriberUserId = userId,
                beneficiaryUserId = beneficiaryId,
                tier = "select",
                status = "active",
            )
            // This user also receives a benefit from benefactorId (they are the beneficiary).
            dataSource.insertBenefit(
                subscriberUserId = benefactorId,
                beneficiaryUserId = userId,
                tier = "social",
                status = "active",
            )

            val facts = repository.facts(userId)

            assertNotNull(facts)
            assertEquals(1, facts.subscriptions.size)
            val subscription = facts.subscriptions.first()
            assertEquals("play_store", subscription.platform)
            assertEquals("select", subscription.tier)
            assertEquals("pynapli_select_monthly", subscription.externalProductId)
            assertEquals("ACKNOWLEDGED", subscription.acknowledgementState)
            assertEquals(1, facts.benefitsGranted.size)
            assertEquals(beneficiaryId, facts.benefitsGranted.first().otherUserId)
            assertEquals(1, facts.benefitsReceived.size)
            assertEquals(benefactorId, facts.benefitsReceived.first().otherUserId)

            repository.recordReviewAudit(actorId = "ops-1", userId = userId, reason = "billing question")
            assertEquals(1, dataSource.adminAuditCount("subscription_support_view", userId))
        }

    @Test
    fun unknownUserReturnsNull() =
        runBlocking {
            val repository = JdbcAdminSubscriptionSupportRepository(dataSource)

            assertNull(repository.facts(UUID.randomUUID()))
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

private fun DataSource.insertSubscription(
    userId: UUID,
    platform: String,
    externalSubscriptionId: String,
    tier: String,
    status: String,
    periodStart: Instant,
    periodEnd: Instant,
    externalProductId: String?,
    externalStatus: String?,
    acknowledgementState: String?,
    lastVerifiedAt: Instant?,
) {
    connection.use { connection ->
        connection
            .prepareStatement(
                """
                INSERT INTO subscriptions (
                    id, user_id, platform, external_subscription_id, tier, status,
                    current_period_start, current_period_end,
                    external_product_id, external_status, acknowledgement_state, last_verified_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, UUID.randomUUID())
                statement.setObject(2, userId)
                statement.setString(3, platform)
                statement.setString(4, externalSubscriptionId)
                statement.setString(5, tier)
                statement.setString(6, status)
                statement.setTimestamp(7, Timestamp.from(periodStart))
                statement.setTimestamp(8, Timestamp.from(periodEnd))
                statement.setString(9, externalProductId)
                statement.setString(10, externalStatus)
                statement.setString(11, acknowledgementState)
                statement.setTimestamp(12, lastVerifiedAt?.let(Timestamp::from))
                statement.executeUpdate()
            }
    }
}

private fun DataSource.insertBenefit(
    subscriberUserId: UUID,
    beneficiaryUserId: UUID,
    tier: String,
    status: String,
) {
    connection.use { connection ->
        connection
            .prepareStatement(
                """
                INSERT INTO linked_partner_benefits (
                    id, subscriber_user_id, beneficiary_user_id, tier, status
                )
                VALUES (?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, UUID.randomUUID())
                statement.setObject(2, subscriberUserId)
                statement.setObject(3, beneficiaryUserId)
                statement.setString(4, tier)
                statement.setString(5, status)
                statement.executeUpdate()
            }
    }
}

private fun DataSource.adminAuditCount(
    action: String,
    targetId: UUID,
): Int =
    connection.use { connection ->
        connection
            .prepareStatement("SELECT count(*) FROM admin_audit_log WHERE action = ? AND target_id = ?")
            .use { statement ->
                statement.setString(1, action)
                statement.setObject(2, targetId)
                statement.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
            }
    }
