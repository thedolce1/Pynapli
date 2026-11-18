package com.pynapli.server.verification

import com.pynapli.server.moderation.JdbcAdminRiskRepository
import com.pynapli.server.safety.JdbcRiskRepository
import com.pynapli.server.safety.RateLimitedAction
import com.pynapli.server.safety.RiskService
import com.pynapli.server.testsupport.PynapliTestDatabase
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * V110 backfills the one-time photo-verification risk reduction for accounts verified before it
 * existed. Flyway ran V110 once on an empty schema, so this runs the migration file's own SQL text
 * against seeded accounts and checks each outcome.
 */
class PhotoVerificationRiskBackfillIntegrationTest {
    private val dataSource: DataSource = PynapliTestDatabase.dataSource
    private val clock = MutableClock(Instant.now().truncatedTo(ChronoUnit.SECONDS))
    private val risk = RiskService(JdbcRiskRepository(dataSource, clock))

    @BeforeTest
    fun reset() {
        PynapliTestDatabase.reset()
    }

    @Test
    fun backfillAppliesTheLiveReductionOnlyWhereItIsOwed() =
        runBlocking {
            // Verified, 25 severe + 30 minor = 55 (friction).
            val mixed = dataSource.insertVerificationUser("Mixed")
            risk.recordImpossibleTravel(mixed, distanceKm = 900.0, elapsedSeconds = 600, speedKmh = 5400.0)
            repeat(5) { risk.recordRateLimitHit(mixed, RateLimitedAction.FIRST_MESSAGE, 30, 20, 3600) }
            dataSource.markPhotoVerified(mixed)

            // Verified, score set by an admin.
            val adminSet = dataSource.insertVerificationUser("AdminSet")
            repeat(5) { risk.recordRateLimitHit(adminSet, RateLimitedAction.FIRST_MESSAGE, 30, 20, 3600) }
            assertNotNull(JdbcAdminRiskRepository(dataSource).setRiskTier(adminSet, "friction", "moderator-1", "spam pattern"))
            dataSource.markPhotoVerified(adminSet)

            // Verified after V109: the live path already recorded the reduction.
            val alreadyCredited = dataSource.insertVerificationUser("Credited")
            risk.recordImpossibleTravel(alreadyCredited, distanceKm = 900.0, elapsedSeconds = 600, speedKmh = 5400.0)
            risk.recordPhotoVerificationPassed(alreadyCredited)
            dataSource.markPhotoVerified(alreadyCredited)

            // Not verified, with points.
            val unverified = dataSource.insertVerificationUser("Unverified")
            risk.recordImpossibleTravel(unverified, distanceKm = 900.0, elapsedSeconds = 600, speedKmh = 5400.0)

            // Verified, never had a risk row.
            val clean = dataSource.insertVerificationUser("Clean")
            dataSource.markPhotoVerified(clean)

            dataSource.runBackfill()

            // Mixed: -20 comes off the 30 minor points first: 35 (watch), 10 decayable, 25 severe.
            assertEquals(Triple(35, "watch", 10), dataSource.riskRow(mixed))
            val mixedEvent = assertNotNull(dataSource.photoEvents(mixed).singleOrNull())
            assertEquals(PhotoEvent("verification", null, RiskService.PHOTO_VERIFIED_DELTA, 35, "watch", true), mixedEvent)
            // The 10 minor points still decay; the 25 severe points stay.
            clock.advance(Duration.ofDays(100))
            assertEquals(RiskService.IMPOSSIBLE_TRAVEL_DELTA, risk.getState(mixed)?.score)

            // Admin-set: untouched, no ledger row.
            assertEquals(Triple(50, "friction", 0), dataSource.riskRow(adminSet))
            assertTrue(dataSource.photoEvents(adminSet).isEmpty())

            // Already credited: still one row (the live one), score unchanged.
            assertEquals(5, dataSource.riskRow(alreadyCredited)?.first)
            assertEquals(listOf(false), dataSource.photoEvents(alreadyCredited).map { it.backfilled })

            // Unverified: untouched.
            assertEquals(Triple(25, "clean", 0), dataSource.riskRow(unverified))
            assertTrue(dataSource.photoEvents(unverified).isEmpty())

            // Clean: like a live approval on a clean account, a 0/clean row and the ledger row, so the
            // credit counts as used.
            assertEquals(Triple(0, "clean", 0), dataSource.riskRow(clean))
            assertEquals(PhotoEvent("verification", null, RiskService.PHOTO_VERIFIED_DELTA, 0, "clean", true), dataSource.photoEvents(clean).single())
        }

    @Test
    fun runningTheBackfillTwiceChangesNothingAndALaterApprovalSubtractsNothing() =
        runBlocking {
            val user = dataSource.insertVerificationUser()
            risk.recordImpossibleTravel(user, distanceKm = 900.0, elapsedSeconds = 600, speedKmh = 5400.0)
            dataSource.markPhotoVerified(user)
            val clean = dataSource.insertVerificationUser("Clean")
            dataSource.markPhotoVerified(clean)

            dataSource.runBackfill()
            assertEquals(Triple(5, "clean", 0), dataSource.riskRow(user))

            dataSource.runBackfill()
            assertEquals(Triple(5, "clean", 0), dataSource.riskRow(user))
            assertEquals(1, dataSource.photoEvents(user).size)
            assertEquals(1, dataSource.photoEvents(clean).size)

            // Revoked and approved again later: the live path finds the backfilled row and skips.
            risk.recordPhotoVerificationPassed(user)
            assertEquals(5, risk.getState(user)?.score)
            assertEquals(1, dataSource.photoEvents(user).size)
        }

    @Test
    fun aLockedAccountIsSkipped() =
        runBlocking {
            val user = dataSource.insertVerificationUser()
            assertNotNull(JdbcAdminRiskRepository(dataSource).lockAccount(user, "moderator-1", "confirmed scam"))
            dataSource.markPhotoVerified(user)

            dataSource.runBackfill()

            assertEquals(Triple(90, "terminate", 0), dataSource.riskRow(user))
            assertTrue(dataSource.photoEvents(user).isEmpty())
        }

    private data class PhotoEvent(
        val source: String,
        val actorUserId: UUID?,
        val delta: Int,
        val scoreAfter: Int,
        val tierAfter: String,
        val backfilled: Boolean,
    )

    private fun DataSource.runBackfill() {
        val sql =
            assertNotNull(PhotoVerificationRiskBackfillIntegrationTest::class.java.classLoader.getResource("db/migration/V110__backfill_photo_verification_risk_reduction.sql"))
                .readText()
        connection.use { connection -> connection.createStatement().use { it.execute(sql) } }
    }

    private fun DataSource.markPhotoVerified(userId: UUID) {
        connection.use { connection ->
            connection
                .prepareStatement("UPDATE profiles SET face_photo_verified_at = now(), verified_at = now() WHERE user_id = ?")
                .use { statement ->
                    statement.setObject(1, userId)
                    assertEquals(1, statement.executeUpdate())
                }
        }
    }

    /** score, tier, decayable_points; null when there is no risk row. */
    private fun DataSource.riskRow(userId: UUID): Triple<Int, String, Int>? =
        connection.use { connection ->
            connection
                .prepareStatement("SELECT score, tier, decayable_points FROM account_risk_states WHERE user_id = ?")
                .use { statement ->
                    statement.setObject(1, userId)
                    statement.executeQuery().use { rs ->
                        if (rs.next()) Triple(rs.getInt(1), rs.getString(2), rs.getInt(3)) else null
                    }
                }
        }

    private fun DataSource.photoEvents(userId: UUID): List<PhotoEvent> =
        connection.use { connection ->
            connection
                .prepareStatement(
                    """
                    SELECT source, actor_user_id, delta, score_after, tier_after,
                           COALESCE(metadata->>'backfill' = 'V110', false) AS backfilled
                    FROM account_risk_events
                    WHERE user_id = ? AND reason = ?
                    ORDER BY created_at
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, userId)
                    statement.setString(2, RiskService.REASON_PHOTO_VERIFIED)
                    statement.executeQuery().use { rs ->
                        buildList {
                            while (rs.next()) {
                                add(
                                    PhotoEvent(
                                        source = rs.getString("source"),
                                        actorUserId = rs.getObject("actor_user_id", UUID::class.java),
                                        delta = rs.getInt("delta"),
                                        scoreAfter = rs.getInt("score_after"),
                                        tierAfter = rs.getString("tier_after"),
                                        backfilled = rs.getBoolean("backfilled"),
                                    ),
                                )
                            }
                        }
                    }
                }
        }
}
