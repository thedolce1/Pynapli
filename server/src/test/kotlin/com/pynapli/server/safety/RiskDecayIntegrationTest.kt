package com.pynapli.server.safety

import com.pynapli.server.auth.JdbcAuthRepository
import com.pynapli.server.auth.security.PasswordHash
import com.pynapli.server.moderation.JdbcAdminRiskRepository
import com.pynapli.server.testsupport.PynapliTestDatabase
import com.pynapli.server.verification.MutableClock
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
 * Risk score decay against real Postgres (TRUST_AND_SAFETY_SPEC "Recovery And Appeals",
 * RiskDecayPolicy): minor (rate-limit) points decay about 1 a day and the tier follows; severe and
 * admin-set points never decay. Time comes from an injected clock; nothing sleeps.
 */
class RiskDecayIntegrationTest {
    private val dataSource: DataSource = PynapliTestDatabase.dataSource
    private val clock = MutableClock(Instant.now().truncatedTo(ChronoUnit.SECONDS))
    private val repository = JdbcRiskRepository(dataSource, clock)
    private val risk = RiskService(repository)
    private val policy = RiskPolicyService(risk)

    @BeforeTest
    fun reset() {
        PynapliTestDatabase.reset()
    }

    @Test
    fun minorPointsDecayOverTimeAndTheTierFollows() =
        runBlocking {
            val user = createUser()
            repeat(9) { risk.recordRateLimitHit(user, RateLimitedAction.FIRST_MESSAGE, 30, 20, 3600) }
            assertEquals(54, state(user).score)
            assertEquals("friction", state(user).tier)
            assertEquals(true, policy.isTravelRestricted(user))

            // Less than one interval: nothing yet.
            clock.advance(Duration.ofHours(23))
            assertEquals(54, state(user).score)

            // 4 days in: 50, still friction.
            clock.advance(Duration.ofHours(73))
            assertEquals(50, state(user).score)
            assertEquals("friction", state(user).tier)

            // One more day drops out of friction into watch, and the friction-tier restriction lifts.
            clock.advance(Duration.ofDays(1))
            assertEquals(49, state(user).score)
            assertEquals("watch", state(user).tier)
            assertEquals(false, policy.isTravelRestricted(user))

            // Out of watch once below 30, and never below zero however long passes.
            clock.advance(Duration.ofDays(20))
            assertEquals(29, state(user).score)
            assertEquals("clean", state(user).tier)
            clock.advance(Duration.ofDays(365))
            assertEquals(0, state(user).score)
            assertEquals("clean", state(user).tier)

            // Every application is in the ledger, and together they account for every point.
            assertEquals(-54, decayDeltaTotal(user))
        }

    @Test
    fun severePointsDoNotDecayHoweverLongPasses() =
        runBlocking {
            val user = createUser()
            risk.recordSevereMediaModerationFlag(UUID.randomUUID(), user, UUID.randomUUID(), "public", "csam_match", "csam")
            assertEquals(70, state(user).score)
            assertEquals("quarantine", state(user).tier)

            clock.advance(Duration.ofDays(3650))

            assertEquals(70, state(user).score)
            assertEquals("quarantine", state(user).tier)
            assertEquals(true, policy.shouldHideFromDiscovery(user))
            assertEquals(0, eventCount(user, RiskDecayPolicy.REASON_DECAY))
        }

    @Test
    fun aMixedScoreDecaysOnlyItsMinorPart() =
        runBlocking {
            val user = createUser()
            risk.recordImpossibleTravel(user, distanceKm = 900.0, elapsedSeconds = 600, speedKmh = 5400.0)
            repeat(5) { risk.recordRateLimitHit(user, RateLimitedAction.FIRST_MESSAGE, 30, 20, 3600) }
            assertEquals(55, state(user).score)
            assertEquals("friction", state(user).tier)

            clock.advance(Duration.ofDays(10))
            assertEquals(45, state(user).score)
            assertEquals("watch", state(user).tier)

            clock.advance(Duration.ofDays(100))
            assertEquals(RiskService.IMPOSSIBLE_TRAVEL_DELTA, state(user).score)
            assertEquals("clean", state(user).tier)
        }

    @Test
    fun newMinorPointsKeepDecayingAtTheSameRate() =
        runBlocking {
            val user = createUser()
            risk.recordRateLimitHit(user, RateLimitedAction.FIRST_MESSAGE, 30, 20, 3600)
            clock.advance(Duration.ofDays(2))
            // The write applies the 2 points due first, then adds 6 more.
            val after = repository.recordEvent(user, null, RiskService.SOURCE_RATE_LIMIT, "rate_limit.first_message", 6)
            assertEquals(10, after.state.score)

            clock.advance(Duration.ofDays(10))
            assertEquals(0, state(user).score)
        }

    @Test
    fun aVerificationReductionComesOffTheMinorPartFirst() =
        runBlocking {
            val user = createUser()
            risk.recordImpossibleTravel(user, distanceKm = 900.0, elapsedSeconds = 600, speedKmh = 5400.0)
            repeat(5) { risk.recordRateLimitHit(user, RateLimitedAction.FIRST_MESSAGE, 30, 20, 3600) }
            risk.recordAgeVerificationPassed(user)
            assertEquals(35, state(user).score)

            // 10 minor points were left; the 25 severe points are untouched by decay.
            clock.advance(Duration.ofDays(100))
            assertEquals(RiskService.IMPOSSIBLE_TRAVEL_DELTA, state(user).score)
        }

    @Test
    fun anAdminSetTierIsNotLiftedByDecay() =
        runBlocking {
            val user = createUser()
            val admin = JdbcAdminRiskRepository(dataSource)
            repeat(5) { risk.recordRateLimitHit(user, RateLimitedAction.FIRST_MESSAGE, 30, 20, 3600) }
            assertEquals("watch", state(user).tier)

            val set = assertNotNull(admin.setRiskTier(user, "friction", "moderator-1", "spam pattern confirmed"))
            assertEquals(50, set.score)

            // The minor points recorded before the admin action were absorbed into the admin score.
            clock.advance(Duration.ofDays(365))
            assertEquals(50, state(user).score)
            assertEquals("friction", state(user).tier)

            // Minor points recorded after it decay, but only back down to the admin-set score.
            risk.recordRateLimitHit(user, RateLimitedAction.FIRST_MESSAGE, 30, 20, 3600)
            assertEquals(56, state(user).score)
            clock.advance(Duration.ofDays(365))
            assertEquals(50, state(user).score)
            assertEquals("friction", state(user).tier)
        }

    @Test
    fun photoVerificationLowersTheScoreOnceAndNeverBelowZero() =
        runBlocking {
            val user = createUser()
            risk.recordImpossibleTravel(user, distanceKm = 900.0, elapsedSeconds = 600, speedKmh = 5400.0)
            risk.recordPhotoVerificationPassed(user)
            assertEquals(5, state(user).score)

            // A second approval (for example after a revoke and a new selfie) changes nothing.
            risk.recordPhotoVerificationPassed(user)
            assertEquals(5, state(user).score)
            assertEquals(1, eventCount(user, RiskService.REASON_PHOTO_VERIFIED))

            // On a clean account the score stays floored at zero.
            val clean = createUser()
            risk.recordPhotoVerificationPassed(clean)
            assertEquals(0, state(clean).score)
            assertEquals("clean", state(clean).tier)
        }

    @Test
    fun concurrentPhotoVerificationSignalsApplyOnce() =
        runBlocking {
            val user = createUser()
            risk.recordImpossibleTravel(user, distanceKm = 900.0, elapsedSeconds = 600, speedKmh = 5400.0)

            coroutineScope { (1..6).map { async { risk.recordPhotoVerificationPassed(user) } }.awaitAll() }

            assertEquals(5, state(user).score)
            assertEquals(1, eventCount(user, RiskService.REASON_PHOTO_VERIFIED))
        }

    @Test
    fun concurrentReadsApplyDecayOnce() =
        runBlocking {
            val user = createUser()
            repeat(5) { risk.recordRateLimitHit(user, RateLimitedAction.FIRST_MESSAGE, 30, 20, 3600) }
            clock.advance(Duration.ofDays(3))

            val reads = coroutineScope { (1..8).map { async { repository.getState(user) } }.awaitAll() }

            // Each read saw either the decayed score or (if it read first) the decay it applied.
            reads.forEach { assertEquals(27, assertNotNull(it).score) }
            assertEquals(27, state(user).score)
            assertEquals(1, eventCount(user, RiskDecayPolicy.REASON_DECAY))
            assertEquals(-3, decayDeltaTotal(user))
        }

    @Test
    fun anAccountWithNoRiskRowHasNoState() =
        runBlocking {
            assertNull(repository.getState(createUser()))
        }

    private suspend fun state(userId: UUID): AccountRiskStateRecord = assertNotNull(repository.getState(userId))

    private fun eventCount(
        userId: UUID,
        reason: String,
    ): Int =
        dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT count(*) FROM account_risk_events WHERE user_id = ? AND reason = ?").use { statement ->
                statement.setObject(1, userId)
                statement.setString(2, reason)
                statement.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }

    private fun decayDeltaTotal(userId: UUID): Int =
        dataSource.connection.use { connection ->
            connection
                .prepareStatement("SELECT COALESCE(sum(delta), 0) FROM account_risk_events WHERE user_id = ? AND reason = ?")
                .use { statement ->
                    statement.setObject(1, userId)
                    statement.setString(2, RiskDecayPolicy.REASON_DECAY)
                    statement.executeQuery().use { rs ->
                        rs.next()
                        rs.getInt(1)
                    }
                }
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
