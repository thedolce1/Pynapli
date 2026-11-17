package com.pynapli.server.auth

import com.pynapli.server.auth.security.PasswordHasher
import com.pynapli.server.testsupport.PynapliTestDatabase
import com.pynapli.shared.network.dto.AuthSessionDto
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Refresh-token rotation must be compare-and-swap: a refresh token is exchangeable exactly once,
 * even when two requests present it at the same moment (two browser tabs sharing localStorage, or a
 * stolen token racing the legitimate client).
 */
class RefreshRotationIntegrationTest {
    private val dataSource = PynapliTestDatabase.dataSource

    @BeforeTest
    fun reset() {
        PynapliTestDatabase.reset()
    }

    private fun authService(repository: AuthRepository = JdbcAuthRepository(dataSource)) = AuthService(repository, passwordHasher = PasswordHasher(iterations = 1_000))

    private suspend fun AuthService.signUp(): AuthSessionDto = signupEmail("rotation@example.com", PASSWORD, "1990-01-01", true, true, true, deviceId = "web", platform = "web", appVersion = "1.0")

    @Test
    fun concurrentRefreshesWithTheSameTokenHaveExactlyOneWinner() =
        runBlocking<Unit> {
            val service = authService()
            var refreshToken = service.signUp().refreshToken

            repeat(ITERATIONS) { iteration ->
                val start = CompletableDeferred<Unit>()
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

                val winners = outcomes.mapNotNull { it.getOrNull() }
                val losers = outcomes.mapNotNull { it.exceptionOrNull() }
                assertEquals(1, winners.size, "iteration $iteration: exactly one refresh may win, got ${winners.size}")
                assertEquals("INVALID_REFRESH_TOKEN", (losers.single() as AuthException).code, "iteration $iteration")
                val winner = winners.single()
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
                object : AuthRepository by jdbc {
                    override suspend fun findSessionByRefreshTokenHash(tokenHash: String): SessionRecord? {
                        val session = jdbc.findSessionByRefreshTokenHash(tokenHash)
                        if (arrived.incrementAndGet() == 2) bothRead.complete(Unit)
                        bothRead.await()
                        return session
                    }
                }
            val service = authService(repository)
            // Signup only creates a session; it never looks one up by refresh token.
            val original = service.signUp()

            val outcomes =
                coroutineScope {
                    List(2) { async(Dispatchers.IO) { runCatching { service.refresh(original.refreshToken) } } }.awaitAll()
                }

            val winners = outcomes.mapNotNull { it.getOrNull() }
            assertEquals(1, winners.size, "exactly one refresh may win, got ${winners.size}")
            assertEquals("INVALID_REFRESH_TOKEN", (outcomes.firstNotNullOf { it.exceptionOrNull() } as AuthException).code)
            service.authenticate(winners.single().accessToken)
        }

    @Test
    fun aRefreshTokenCannotBeReusedAfterRotation() =
        runBlocking<Unit> {
            val service = authService()
            val original = service.signUp()

            val rotated = service.refresh(original.refreshToken)

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
                        sessionId: java.util.UUID,
                        presentedRefreshTokenHash: String,
                        accessTokenHash: String,
                        refreshTokenHash: String,
                        accessExpiresAt: Instant,
                        refreshExpiresAt: Instant,
                    ): SessionRecord? {
                        oldAccessToken?.let { service.authenticate(it) }
                        return JdbcAuthRepository(dataSource).rotateSession(
                            sessionId = sessionId,
                            presentedRefreshTokenHash = presentedRefreshTokenHash,
                            accessTokenHash = accessTokenHash,
                            refreshTokenHash = refreshTokenHash,
                            accessExpiresAt = accessExpiresAt,
                            refreshExpiresAt = refreshExpiresAt,
                        )
                    }
                }
            service = authService(repository)
            val original = service.signUp()
            oldAccessToken = original.accessToken

            service.refresh(original.refreshToken)

            assertEquals("UNAUTHENTICATED", assertFailsWith<AuthException> { service.authenticate(original.accessToken) }.code)
        }

    private companion object {
        const val PASSWORD = "correct horse battery staple"
        const val ITERATIONS = 30
    }
}
