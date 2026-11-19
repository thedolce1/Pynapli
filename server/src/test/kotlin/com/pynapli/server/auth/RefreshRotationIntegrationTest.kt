package com.pynapli.server.auth

import com.pynapli.server.auth.security.PasswordHasher
import com.pynapli.server.auth.security.Sha256
import com.pynapli.server.moderation.revokeUserSessions
import com.pynapli.server.testsupport.PynapliTestDatabase
import com.pynapli.server.testsupport.signUpVerified
import com.pynapli.shared.network.dto.AuthSessionDto
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * Refresh-token rotation is compare-and-swap: a refresh token is exchanged for new tokens exactly
 * once, even when several requests present it at the same moment (two browser tabs sharing
 * localStorage, or a stolen token racing the legitimate client).
 *
 * The exchange is also idempotent for [AUTH_REFRESH_REPLAY_GRACE]: presenting the consumed token again
 * (the first response was lost, or the caller lost the race) returns the SAME tokens the one rotation
 * issued, never a second set, and only for the single most recent previous token of a live session.
 */
class RefreshRotationIntegrationTest {
    private val dataSource = PynapliTestDatabase.dataSource

    // Whole seconds: Postgres keeps microseconds, so a sub-microsecond start could round a stored expiry
    // past the instant the boundary test compares it with.
    private val clock = RefreshTestClock(Instant.now().truncatedTo(ChronoUnit.SECONDS))

    @BeforeTest
    fun reset() {
        PynapliTestDatabase.reset()
    }

    private fun authService(repository: AuthRepository = JdbcAuthRepository(dataSource)) = AuthService(repository, passwordHasher = PasswordHasher(iterations = 1_000), clock = clock)

    private suspend fun AuthService.signUp(): AuthSessionDto = signUpVerified("rotation@example.com", PASSWORD, deviceId = "web", platform = "web", appVersion = "1.0")

    @Test
    fun concurrentRefreshesWithTheSameTokenHaveExactlyOneWinner() =
        runBlocking<Unit> {
            val repository = RotationCountingRepository(JdbcAuthRepository(dataSource))
            val service = authService(repository)
            var refreshToken = service.signUp().refreshToken

            repeat(ITERATIONS) { iteration ->
                val start = CompletableDeferred<Unit>()
                val rotationsBefore = repository.rotations.get()
                val outcomes =
                    coroutineScope {
                        val calls =
                            List(2) {
                                async(Dispatchers.IO) {
                                    start.await()
                                    runCatching { service.refresh(refreshToken) }
                                }
                            }
                        start.complete(Unit)
                        calls.awaitAll()
                    }

                // Exactly one rotation wins; the other caller converges on the winner's tokens instead
                // of being rejected (it used to get INVALID_REFRESH_TOKEN).
                assertEquals(1, repository.rotations.get() - rotationsBefore, "iteration $iteration: exactly one rotation may win")
                val sessions = outcomes.map { it.getOrThrow() }
                assertEquals(1, sessions.map { it.accessToken to it.refreshToken }.toSet().size, "iteration $iteration: both callers get the same tokens")
                val winner = sessions.first()
                // The winner's tokens are the live ones — not overwritten by the loser.
                service.authenticate(winner.accessToken)
                refreshToken = winner.refreshToken
            }
        }

    @Test
    fun bothCallersHavingReadTheSessionStillYieldsExactlyOneWinner() =
        runBlocking<Unit> {
            // Deterministic form of the race above: hold every caller just after it has looked the
            // session up, so both reach the rotate step having read the same (still-current) row.
            val jdbc = JdbcAuthRepository(dataSource)
            val arrived = AtomicInteger(0)
            val bothRead = CompletableDeferred<Unit>()
            val repository =
                RotationCountingRepository(
                    object : AuthRepository by jdbc {
                        override suspend fun findSessionByRefreshTokenHash(tokenHash: String): SessionRecord? {
                            val session = jdbc.findSessionByRefreshTokenHash(tokenHash)
                            if (arrived.incrementAndGet() == 2) bothRead.complete(Unit)
                            bothRead.await()
                            return session
                        }
                    },
                )
            val service = authService(repository)
            // Signup only creates a session; it never looks one up by refresh token.
            val original = service.signUp()

            val outcomes =
                coroutineScope {
                    List(2) { async(Dispatchers.IO) { runCatching { service.refresh(original.refreshToken) } } }.awaitAll()
                }

            // One rotation; the compare-and-swap loser replays the winner's tokens.
            assertEquals(1, repository.rotations.get(), "exactly one rotation may win")
            val sessions = outcomes.map { it.getOrThrow() }
            assertEquals(1, sessions.map { it.accessToken to it.refreshToken }.toSet().size)
            service.authenticate(sessions.first().accessToken)
        }

    @Test
    fun aRefreshTokenCannotBeReusedAfterRotation() =
        runBlocking<Unit> {
            val service = authService()
            val original = service.signUp()

            val rotated = service.refresh(original.refreshToken)
            // Inside the grace the consumed token replays the same session (see the tests below); once
            // the grace has passed it is rejected exactly as before.
            clock.advance(AUTH_REFRESH_REPLAY_GRACE.plusSeconds(1))

            assertEquals("INVALID_REFRESH_TOKEN", assertFailsWith<AuthException> { service.refresh(original.refreshToken) }.code)
            service.authenticate(rotated.accessToken)
        }

    @Test
    fun theOldAccessTokenIsNotServedFromCacheAfterRotation() =
        runBlocking<Unit> {
            // Simulates an authenticate() landing between refresh()'s early cache eviction and the
            // rotate commit: it re-caches the still-valid old access token. After the rotation commits,
            // that token must not keep authenticating out of the cache.
            lateinit var service: AuthService
            var oldAccessToken: String? = null
            val repository =
                object : AuthRepository by JdbcAuthRepository(dataSource) {
                    override suspend fun rotateSession(
                        sessionId: UUID,
                        presentedRefreshTokenHash: String,
                        accessTokenHash: String,
                        refreshTokenHash: String,
                        accessExpiresAt: Instant,
                        refreshExpiresAt: Instant,
                        replayNonce: ByteArray,
                        replayCiphertext: ByteArray,
                        replayExpiresAt: Instant,
                    ): SessionRecord? {
                        oldAccessToken?.let { service.authenticate(it) }
                        return JdbcAuthRepository(dataSource).rotateSession(
                            sessionId = sessionId,
                            presentedRefreshTokenHash = presentedRefreshTokenHash,
                            accessTokenHash = accessTokenHash,
                            refreshTokenHash = refreshTokenHash,
                            accessExpiresAt = accessExpiresAt,
                            refreshExpiresAt = refreshExpiresAt,
                            replayNonce = replayNonce,
                            replayCiphertext = replayCiphertext,
                            replayExpiresAt = replayExpiresAt,
                        )
                    }
                }
            service = authService(repository)
            val original = service.signUp()
            oldAccessToken = original.accessToken

            service.refresh(original.refreshToken)

            assertEquals("UNAUTHENTICATED", assertFailsWith<AuthException> { service.authenticate(original.accessToken) }.code)
        }

    @Test
    fun aRetriedRefreshInsideTheGraceReturnsTheSameTokensAndChangesNothing() =
        runBlocking<Unit> {
            val repository = RotationCountingRepository(JdbcAuthRepository(dataSource))
            val service = authService(repository)
            val original = service.signUp()
            val userId = UUID.fromString(original.user.userId)

            val first = service.refresh(original.refreshToken)
            val rowAfterRotation = sessionRows(userId)
            clock.advance(AUTH_REFRESH_REPLAY_GRACE.minusSeconds(1))

            // The first response was "lost": the client retries with the token it still holds.
            val retried = service.refresh(original.refreshToken)

            assertEquals(first.accessToken, retried.accessToken)
            assertEquals(first.refreshToken, retried.refreshToken)
            assertEquals(first.user, retried.user)
            assertEquals(1, repository.rotations.get(), "a replay must not rotate again")
            // Nothing minted, nothing written: same single row, byte for byte.
            assertEquals(rowAfterRotation, sessionRows(userId))
            assertEquals(1, rowAfterRotation.size)
            assertNull(rowAfterRotation.single().revokedAt)
            // And the tokens it returned are the live ones.
            assertEquals(userId, service.authenticate(retried.accessToken).id)
            val next = service.refresh(retried.refreshToken)
            service.authenticate(next.accessToken)
        }

    @Test
    fun aRetriedRefreshOnceTheGraceEndsIsRejected() =
        runBlocking<Unit> {
            val service = authService()
            val original = service.signUp()
            val first = service.refresh(original.refreshToken)

            clock.advance(AUTH_REFRESH_REPLAY_GRACE)

            assertEquals("INVALID_REFRESH_TOKEN", assertFailsWith<AuthException> { service.refresh(original.refreshToken) }.code)
            // The session itself is untouched.
            service.authenticate(first.accessToken)
        }

    @Test
    fun aTokenTwoRotationsOldIsRejectedEvenInsideTheGrace() =
        runBlocking<Unit> {
            val service = authService()
            val original = service.signUp()
            val first = service.refresh(original.refreshToken)
            val second = service.refresh(first.refreshToken)

            assertEquals("INVALID_REFRESH_TOKEN", assertFailsWith<AuthException> { service.refresh(original.refreshToken) }.code)
            // Only the single most recent previous token replays, and it gets the current tokens.
            val replayed = service.refresh(first.refreshToken)
            assertEquals(second.accessToken, replayed.accessToken)
            assertEquals(second.refreshToken, replayed.refreshToken)
        }

    @Test
    fun aReplayAfterLogoutIsRejected() =
        runBlocking<Unit> {
            val service = authService()
            val original = service.signUp()
            val first = service.refresh(original.refreshToken)

            service.logout(first.accessToken)

            assertEquals("INVALID_REFRESH_TOKEN", assertFailsWith<AuthException> { service.refresh(original.refreshToken) }.code)
            assertEquals("INVALID_REFRESH_TOKEN", assertFailsWith<AuthException> { service.refresh(first.refreshToken) }.code)
            assertReplayCleared(UUID.fromString(original.user.userId))
        }

    @Test
    fun aReplayAfterAccountWideRevocationIsRejected() =
        runBlocking<Unit> {
            val service = authService()
            val original = service.signUp()
            val userId = UUID.fromString(original.user.userId)
            service.refresh(original.refreshToken)

            dataSource.connection.use { it.revokeUserSessions(userId) }

            assertEquals("INVALID_REFRESH_TOKEN", assertFailsWith<AuthException> { service.refresh(original.refreshToken) }.code)
            assertReplayCleared(userId)
        }

    @Test
    fun aPasswordChangeEndsTheReplayEvenForTheSessionItKeeps() =
        runBlocking<Unit> {
            val service = authService()
            val original = service.signUp()
            val userId = UUID.fromString(original.user.userId)
            val first = service.refresh(original.refreshToken)

            JdbcPasswordRecoveryRepository(dataSource).changePassword(
                userId = userId,
                passwordHash = PasswordHasher(iterations = 1_000).hash("a brand new passphrase"),
                keepAccessTokenHash = Sha256.hash(first.accessToken),
            )

            assertEquals("INVALID_REFRESH_TOKEN", assertFailsWith<AuthException> { service.refresh(original.refreshToken) }.code)
            assertReplayCleared(userId)
            // The kept session itself stays signed in.
            service.authenticate(first.accessToken)
        }

    @Test
    fun aReplayForANonActiveAccountIsRefusedLikeANormalRefresh() =
        runBlocking<Unit> {
            val service = authService()
            val original = service.signUp()
            val userId = UUID.fromString(original.user.userId)
            val first = service.refresh(original.refreshToken)

            setUserStatus(userId, "suspended")

            assertEquals("ACCOUNT_NOT_ACTIVE", assertFailsWith<AuthException> { service.refresh(original.refreshToken) }.code)
            assertEquals("ACCOUNT_NOT_ACTIVE", assertFailsWith<AuthException> { service.refresh(first.refreshToken) }.code)
        }

    @Test
    fun aReplayCannotResurrectAnExpiredSession() =
        runBlocking<Unit> {
            val service = authService()
            val original = service.signUp()
            service.refresh(original.refreshToken)

            // The session's refresh lifetime has ended, though the replay grace has not.
            exec("UPDATE user_sessions SET refresh_expires_at = ? WHERE user_id = ?", java.sql.Timestamp.from(clock.instant()), UUID.fromString(original.user.userId))

            assertEquals("INVALID_REFRESH_TOKEN", assertFailsWith<AuthException> { service.refresh(original.refreshToken) }.code)
        }

    @Test
    fun aTamperedBlobOrAWrongTokenCannotOpenTheReplay() =
        runBlocking<Unit> {
            val service = authService()
            val original = service.signUp()
            val userId = UUID.fromString(original.user.userId)
            val first = service.refresh(original.refreshToken)

            // A token whose hash matches the previous-token column but is not the token the blob was
            // sealed under: the key differs, so GCM refuses it.
            val impostor = "impostor-refresh-token"
            exec("UPDATE user_sessions SET previous_refresh_token_hash = ? WHERE user_id = ?", Sha256.hash(impostor), userId)
            assertEquals("INVALID_REFRESH_TOKEN", assertFailsWith<AuthException> { service.refresh(impostor) }.code)

            // The right token, but one flipped bit in the stored ciphertext.
            exec("UPDATE user_sessions SET previous_refresh_token_hash = ? WHERE user_id = ?", Sha256.hash(original.refreshToken), userId)
            exec(
                "UPDATE user_sessions SET replay_ciphertext = set_byte(replay_ciphertext, 0, get_byte(replay_ciphertext, 0) # 1) WHERE user_id = ?",
                userId,
            )
            assertEquals("INVALID_REFRESH_TOKEN", assertFailsWith<AuthException> { service.refresh(original.refreshToken) }.code)

            service.authenticate(first.accessToken)
        }

    @Test
    fun manyConcurrentRefreshesWithOneTokenRotateOnceAndAllGetTheSameTokens() =
        runBlocking<Unit> {
            val repository = RotationCountingRepository(JdbcAuthRepository(dataSource))
            val service = authService(repository)
            val original = service.signUp()
            val start = CompletableDeferred<Unit>()

            val sessions =
                coroutineScope {
                    val calls =
                        List(CONCURRENT_CALLERS) {
                            async(Dispatchers.IO) {
                                start.await()
                                service.refresh(original.refreshToken)
                            }
                        }
                    start.complete(Unit)
                    calls.awaitAll()
                }

            assertEquals(1, repository.rotations.get())
            assertEquals(1, sessions.map { it.accessToken to it.refreshToken }.toSet().size)
            assertNotEquals(original.refreshToken, sessions.first().refreshToken)
            assertEquals(1, sessionRows(UUID.fromString(original.user.userId)).size)
            service.authenticate(sessions.first().accessToken)
        }

    @Test
    fun theStoredReplayDoesNotHoldTheTokensInClear() =
        runBlocking<Unit> {
            val service = authService()
            val original = service.signUp()
            val first = service.refresh(original.refreshToken)

            val (previousHash, nonce, ciphertext) =
                dataSource.connection.use { connection ->
                    connection
                        .prepareStatement("SELECT previous_refresh_token_hash, replay_nonce, replay_ciphertext FROM user_sessions WHERE user_id = ?")
                        .use { statement ->
                            statement.setObject(1, UUID.fromString(original.user.userId))
                            statement.executeQuery().use { rs ->
                                rs.next()
                                Triple(rs.getString(1), rs.getBytes(2), rs.getBytes(3))
                            }
                        }
                }

            // The consumed token is stored only as its lookup hash.
            assertEquals(Sha256.hash(original.refreshToken), previousHash)
            for (stored in listOf(nonce, ciphertext)) {
                for (text in listOf(String(stored, Charsets.ISO_8859_1), String(stored, Charsets.UTF_8))) {
                    for (token in listOf(first.accessToken, first.refreshToken, original.refreshToken)) {
                        assertFalse(text.contains(token), "the sealed replay must not contain a token in clear")
                    }
                }
            }
        }

    private fun sessionRows(userId: UUID): List<SessionRow> =
        dataSource.connection.use { connection ->
            connection
                .prepareStatement(
                    """
                    SELECT access_token_hash, refresh_token_hash, access_expires_at::text, refresh_expires_at::text,
                           previous_refresh_token_hash, encode(replay_nonce, 'hex'), encode(replay_ciphertext, 'hex'),
                           replay_expires_at::text, revoked_at::text, last_seen_at::text
                    FROM user_sessions
                    WHERE user_id = ?
                    ORDER BY id
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, userId)
                    statement.executeQuery().use { rs ->
                        buildList {
                            while (rs.next()) add(SessionRow(columns = (1..10).map { rs.getString(it) }, revokedAt = rs.getString(9)))
                        }
                    }
                }
        }

    private fun assertReplayCleared(userId: UUID) {
        dataSource.connection.use { connection ->
            connection
                .prepareStatement(
                    "SELECT count(*) FROM user_sessions WHERE user_id = ? AND (previous_refresh_token_hash IS NOT NULL OR replay_ciphertext IS NOT NULL)",
                ).use { statement ->
                    statement.setObject(1, userId)
                    statement.executeQuery().use { rs ->
                        rs.next()
                        assertEquals(0, rs.getInt(1), "revocation must clear the sealed replay")
                    }
                }
        }
    }

    private fun setUserStatus(
        userId: UUID,
        status: String,
    ) = exec("UPDATE users SET status = ? WHERE id = ?", status, userId)

    private fun exec(
        sql: String,
        vararg params: Any,
    ) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                params.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
                statement.executeUpdate()
            }
        }
    }

    private data class SessionRow(
        val columns: List<String?>,
        val revokedAt: String?,
    )

    private companion object {
        const val PASSWORD = "correct horse battery staple"
        const val ITERATIONS = 30
        const val CONCURRENT_CALLERS = 8
    }
}

/** Counts the rotations that actually committed (the compare-and-swap winners). */
internal class RotationCountingRepository(
    private val delegate: AuthRepository,
) : AuthRepository by delegate {
    val rotations = AtomicInteger(0)

    override suspend fun rotateSession(
        sessionId: UUID,
        presentedRefreshTokenHash: String,
        accessTokenHash: String,
        refreshTokenHash: String,
        accessExpiresAt: Instant,
        refreshExpiresAt: Instant,
        replayNonce: ByteArray,
        replayCiphertext: ByteArray,
        replayExpiresAt: Instant,
    ): SessionRecord? =
        delegate
            .rotateSession(
                sessionId = sessionId,
                presentedRefreshTokenHash = presentedRefreshTokenHash,
                accessTokenHash = accessTokenHash,
                refreshTokenHash = refreshTokenHash,
                accessExpiresAt = accessExpiresAt,
                refreshExpiresAt = refreshExpiresAt,
                replayNonce = replayNonce,
                replayCiphertext = replayCiphertext,
                replayExpiresAt = replayExpiresAt,
            ).also { if (it != null) rotations.incrementAndGet() }
}

/** A clock the tests move by hand, so the grace is crossed without sleeping. */
internal class RefreshTestClock(
    private var now: Instant,
) : Clock() {
    fun advance(duration: Duration) {
        now = now.plus(duration)
    }

    override fun instant(): Instant = now

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId?): Clock = this
}
