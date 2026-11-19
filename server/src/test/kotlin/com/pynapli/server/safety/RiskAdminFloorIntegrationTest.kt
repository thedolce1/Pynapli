package com.pynapli.server.safety

import com.pynapli.server.auth.JdbcAuthRepository
import com.pynapli.server.auth.security.PasswordHash
import com.pynapli.server.moderation.JdbcAdminRiskRepository
import com.pynapli.server.testsupport.PynapliTestDatabase
import com.pynapli.server.verification.MutableClock
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Verification must not lower an admin-set score (founder decision, TRUST_AND_SAFETY_SPEC): admin
 * set-tier, lock and ban write `admin_floor_score`, and a negative delta stops there. V112 backfills
 * the floor for accounts an admin acted on before it existed.
 */
class RiskAdminFloorIntegrationTest {
    private val dataSource: DataSource = PynapliTestDatabase.dataSource
    private val clock = MutableClock(Instant.now().truncatedTo(ChronoUnit.SECONDS))
    private val repository = JdbcRiskRepository(dataSource, clock)
    private val risk = RiskService(repository)
    private val policy = RiskPolicyService(risk)
    private val admin = JdbcAdminRiskRepository(dataSource)

    @BeforeTest
    fun reset() {
        PynapliTestDatabase.reset()
    }

    @Test
    fun anAdminQuarantineIsNotLoweredByAgeVerification() =
        runBlocking {
            val user = quarantinedByAdmin()

            risk.recordAgeVerificationPassed(user)

            assertEquals(70, state(user).score)
            assertEquals("quarantine", state(user).tier)
            assertEquals(true, policy.isSocialActionRestricted(user))
            assertEquals(listOf(LedgerRow(0, 70, "-20", "70", "true")), ledger(user, "verification.age_verified"))
        }

    @Test
    fun anAdminQuarantineIsNotLoweredByPhotoVerificationAndTheCreditIsUsed() =
        runBlocking {
            val user = quarantinedByAdmin()

            risk.recordPhotoVerificationPassed(user)
            assertEquals(70, state(user).score)
            assertEquals(listOf(LedgerRow(0, 70, "-20", "70", "true")), ledger(user, RiskService.REASON_PHOTO_VERIFIED))

            // Cleared by an admin later, then approved again: the credit was already used.
            assertNotNull(admin.setRiskTier(user, "clean", "moderator-1", "appeal accepted"))
            risk.recordImpossibleTravel(user, distanceKm = 900.0, elapsedSeconds = 600, speedKmh = 5400.0)
            risk.recordPhotoVerificationPassed(user)
            assertEquals(RiskService.IMPOSSIBLE_TRAVEL_DELTA, state(user).score)
            assertEquals(1, ledger(user, RiskService.REASON_PHOTO_VERIFIED).size)
        }

    @Test
    fun anAdminQuarantineIsNotLoweredByBothVerifications() =
        runBlocking {
            val user = quarantinedByAdmin()

            risk.recordAgeVerificationPassed(user)
            risk.recordPhotoVerificationPassed(user)

            assertEquals(70, state(user).score)
            assertEquals("quarantine", state(user).tier)
            assertEquals(listOf(0), ledger(user, "verification.age_verified").map { it.delta })
            assertEquals(listOf(0), ledger(user, RiskService.REASON_PHOTO_VERIFIED).map { it.delta })
        }

    @Test
    fun pointsAddedAfterTheAdminActionAreReducedOnlyDownToTheFloor() =
        runBlocking {
            val user = quarantinedByAdmin()
            repeat(3) { risk.recordRateLimitHit(user, RateLimitedAction.FIRST_MESSAGE, 30, 20, 3600) }
            assertEquals(88, state(user).score)

            risk.recordAgeVerificationPassed(user)

            // 18 of the 20 applied; the ledger records what applied, the metadata what was asked.
            assertEquals(70, state(user).score)
            assertEquals(listOf(LedgerRow(-18, 70, "-20", "70", "true")), ledger(user, "verification.age_verified"))
            clock.advance(Duration.ofDays(365))
            assertEquals(70, state(user).score)
        }

    @Test
    fun aReductionTheFloorDoesNotLimitIsRecordedAsBefore() =
        runBlocking {
            val user = quarantinedByAdmin()
            repeat(5) { risk.recordRateLimitHit(user, RateLimitedAction.FIRST_MESSAGE, 30, 20, 3600) }
            assertEquals(100, state(user).score)

            risk.recordAgeVerificationPassed(user)
            assertEquals(80, state(user).score)
            assertEquals(listOf(LedgerRow(-20, 80, null, null, null)), ledger(user, "verification.age_verified"))

            // The 10 minor points left decay, but only back to the admin score.
            clock.advance(Duration.ofDays(365))
            assertEquals(70, state(user).score)
            assertEquals("quarantine", state(user).tier)
        }

    @Test
    fun anAdminSettingCleanRemovesTheProtection() =
        runBlocking {
            val user = quarantinedByAdmin()
            assertNotNull(admin.setRiskTier(user, "clean", "moderator-1", "appeal accepted"))
            assertEquals(0, adminFloor(user))
            risk.recordImpossibleTravel(user, distanceKm = 900.0, elapsedSeconds = 600, speedKmh = 5400.0)

            risk.recordAgeVerificationPassed(user)

            assertEquals(5, state(user).score)
            assertEquals(listOf(LedgerRow(-20, 5, null, null, null)), ledger(user, "verification.age_verified"))
        }

    @Test
    fun lockSetsTheFloorAndMarkReviewedLeavesItAlone() =
        runBlocking {
            val locked = createUser()
            assertNotNull(admin.lockAccount(locked, "moderator-1", "confirmed scam"))
            assertEquals(90, adminFloor(locked))
            risk.recordAgeVerificationPassed(locked)
            assertEquals(90, state(locked).score)

            val reviewed = quarantinedByAdmin()
            assertNotNull(admin.markRiskReviewed(reviewed, "moderator-1", "looked again"))
            assertEquals(70, adminFloor(reviewed))
        }

    @Test
    fun anAccountNoAdminTouchedBehavesAsBefore() =
        runBlocking {
            val user = createUser()
            risk.recordImpossibleTravel(user, distanceKm = 900.0, elapsedSeconds = 600, speedKmh = 5400.0)
            risk.recordAgeVerificationPassed(user)
            assertEquals(5, state(user).score)
            assertNull(adminFloor(user))
            assertEquals(listOf(LedgerRow(-20, 5, null, null, null)), ledger(user, "verification.age_verified"))

            // Floored at zero, and the ledger keeps the requested delta, as it always has.
            val clean = createUser()
            risk.recordAgeVerificationPassed(clean)
            assertEquals(0, state(clean).score)
            assertEquals(listOf(LedgerRow(-20, 0, null, null, null)), ledger(clean, "verification.age_verified"))
        }

    @Test
    fun v112SetsTheFloorFromTheLatestAdminEventAndChangesNoScore() =
        runBlocking {
            // Admin quarantine, then (before V112) a verification took it to 50.
            val lowered = quarantinedByAdmin()
            dataSource.update("UPDATE account_risk_states SET score = 50, tier = 'friction', admin_floor_score = NULL WHERE user_id = ?", lowered)
            // Two admin actions: the latest (quarantine) wins.
            val twice = createUser()
            assertNotNull(admin.setRiskTier(twice, "friction", "moderator-1", "first look"))
            // Separate transactions, so the database clock (created_at) orders the two events.
            assertNotNull(admin.setRiskTier(twice, "quarantine", "moderator-1", "second look"))
            // Locked.
            val locked = createUser()
            assertNotNull(admin.lockAccount(locked, "moderator-1", "confirmed scam"))
            // Reviewed only, never set.
            val reviewedOnly = createUser()
            risk.recordImpossibleTravel(reviewedOnly, distanceKm = 900.0, elapsedSeconds = 600, speedKmh = 5400.0)
            assertNotNull(admin.markRiskReviewed(reviewedOnly, "moderator-1", "fine"))
            // No admin at all.
            val untouched = createUser()
            risk.recordImpossibleTravel(untouched, distanceKm = 900.0, elapsedSeconds = 600, speedKmh = 5400.0)
            listOf(twice, locked, reviewedOnly, untouched).forEach {
                dataSource.update("UPDATE account_risk_states SET admin_floor_score = NULL WHERE user_id = ?", it)
            }

            dataSource.runV112Backfill()

            assertEquals(70, adminFloor(lowered))
            assertEquals(50, state(lowered).score) // not restored
            assertEquals(70, adminFloor(twice))
            assertEquals(70, state(twice).score)
            assertEquals(90, adminFloor(locked))
            assertEquals(90, state(locked).score)
            assertNull(adminFloor(reviewedOnly))
            assertEquals(25, state(reviewedOnly).score)
            assertNull(adminFloor(untouched))
            assertEquals(25, state(untouched).score)

            // An account already below its floor: a reduction applies nothing and never raises it.
            risk.recordAgeVerificationPassed(lowered)
            assertEquals(50, state(lowered).score)
            assertEquals(listOf(LedgerRow(0, 50, "-20", "70", "true")), ledger(lowered, "verification.age_verified"))
        }

    private suspend fun quarantinedByAdmin(): UUID {
        val user = createUser()
        assertEquals(70, assertNotNull(admin.setRiskTier(user, "quarantine", "moderator-1", "confirmed spam")).score)
        assertEquals(70, adminFloor(user))
        return user
    }

    private suspend fun state(userId: UUID): AccountRiskStateRecord = assertNotNull(repository.getState(userId))

    private data class LedgerRow(
        val delta: Int,
        val scoreAfter: Int,
        val requestedDelta: String?,
        val adminFloorScore: String?,
        val limitedByAdminFloor: String?,
    )

    private fun ledger(
        userId: UUID,
        reason: String,
    ): List<LedgerRow> =
        dataSource.connection.use { connection ->
            connection
                .prepareStatement(
                    """
                    SELECT delta,
                           score_after,
                           metadata->>'requestedDelta' AS requested_delta,
                           metadata->>'adminFloorScore' AS admin_floor_score,
                           metadata->>'limitedByAdminFloor' AS limited
                    FROM account_risk_events
                    WHERE user_id = ? AND reason = ?
                    ORDER BY created_at
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, userId)
                    statement.setString(2, reason)
                    statement.executeQuery().use { rs ->
                        buildList {
                            while (rs.next()) {
                                add(
                                    LedgerRow(
                                        delta = rs.getInt("delta"),
                                        scoreAfter = rs.getInt("score_after"),
                                        requestedDelta = rs.getString("requested_delta"),
                                        adminFloorScore = rs.getString("admin_floor_score"),
                                        limitedByAdminFloor = rs.getString("limited"),
                                    ),
                                )
                            }
                        }
                    }
                }
        }

    private fun adminFloor(userId: UUID): Int? =
        dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT admin_floor_score FROM account_risk_states WHERE user_id = ?").use { statement ->
                statement.setObject(1, userId)
                statement.executeQuery().use { rs ->
                    if (rs.next()) rs.getInt(1).takeUnless { rs.wasNull() } else null
                }
            }
        }

    private fun DataSource.update(
        sql: String,
        userId: UUID,
    ) {
        connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setObject(1, userId)
                assertEquals(1, statement.executeUpdate())
            }
        }
    }

    /** Runs V112's backfill UPDATE from the migration file (the ALTERs already ran under Flyway). */
    private fun DataSource.runV112Backfill() {
        val migration =
            assertNotNull(
                RiskAdminFloorIntegrationTest::class.java.classLoader.getResource("db/migration/V112__add_risk_admin_floor_score.sql"),
            ).readText()
        val statement = migration.substring(migration.indexOf("UPDATE account_risk_states s")).trim().removeSuffix(";")
        connection.use { connection -> connection.createStatement().use { it.execute(statement) } }
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
