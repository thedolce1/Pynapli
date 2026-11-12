package com.pynapli.server.auth

import com.pynapli.server.auth.security.PasswordHasher
import com.pynapli.server.testsupport.PynapliTestDatabase
import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PasswordRecoveryIntegrationTest {
    private val dataSource = PynapliTestDatabase.dataSource
    private val hasher = PasswordHasher(iterations = 1_000)
    private val authService = AuthService(JdbcAuthRepository(dataSource), passwordHasher = hasher)
    private val mailer = CapturingMailer()
    private var nextCode = "123456"
    private val revoked = mutableListOf<UUID>()

    private fun recovery(
        clock: Clock = Clock.systemUTC(),
        resetMailer: PasswordResetMailer = mailer,
    ) = PasswordRecoveryService(
        repository = JdbcPasswordRecoveryRepository(dataSource),
        mailer = resetMailer,
        passwordHasher = hasher,
        clock = clock,
        onSessionsRevoked = { revoked += it },
        codeGenerator = { nextCode },
    )

    @BeforeTest
    fun reset() {
        PynapliTestDatabase.reset()
    }

    private suspend fun signUp(email: String = "reset@example.com") =
        authService.signupEmail(
            email = email,
            password = OLD_PASSWORD,
            birthdate = "1990-01-01",
            confirmedAdult = true,
            acceptedTerms = true,
            acceptedPrivacy = true,
            deviceId = null,
            platform = "test",
            appVersion = "1",
        )

    private suspend fun signIn(
        password: String,
        email: String = "reset@example.com",
    ) = authService.loginEmail(email, password, null, "test", "1")

    @Test
    fun aCodeFromTheEmailSetsANewPasswordAndSignsOutEverywhere() =
        runBlocking<Unit> {
            val session = signUp()
            recovery().requestReset(" Reset@Example.com ", deviceId = null)

            assertEquals(listOf("Reset@Example.com" to "123456"), mailer.sent)
            recovery().resetPassword("reset@example.com", "123456", NEW_PASSWORD)

            assertFailsWith<AuthException> { signIn(OLD_PASSWORD) }
            signIn(NEW_PASSWORD)
            val error = assertFailsWith<AuthException> { authService.refresh(session.refreshToken) }
            assertEquals("INVALID_REFRESH_TOKEN", error.code)
            assertEquals(listOf(UUID.fromString(session.user.userId)), revoked)
        }

    @Test
    fun aCodeWorksOnce() =
        runBlocking<Unit> {
            signUp()
            recovery().requestReset("reset@example.com", null)
            recovery().resetPassword("reset@example.com", "123456", NEW_PASSWORD)

            val error = assertFailsWith<AuthException> { recovery().resetPassword("reset@example.com", "123456", "another-password-1") }
            assertEquals("INVALID_RESET_CODE", error.code)
        }

    @Test
    fun anUnknownEmailLooksTheSameButSendsNothing() =
        runBlocking<Unit> {
            recovery().requestReset("nobody@example.com", null)

            assertTrue(mailer.sent.isEmpty())
        }

    @Test
    fun aNewCodeRetiresTheOldOne() =
        runBlocking<Unit> {
            signUp()
            recovery().requestReset("reset@example.com", null)
            nextCode = "654321"
            recovery().requestReset("reset@example.com", null)

            assertFailsWith<AuthException> { recovery().resetPassword("reset@example.com", "123456", NEW_PASSWORD) }
            recovery().resetPassword("reset@example.com", "654321", NEW_PASSWORD)
            signIn(NEW_PASSWORD)
        }

    @Test
    fun fiveWrongGuessesKillTheCode() =
        runBlocking<Unit> {
            signUp()
            recovery().requestReset("reset@example.com", null)
            repeat(PasswordRecoveryService.MAX_FAILED_ATTEMPTS) {
                assertFailsWith<AuthException> { recovery().resetPassword("reset@example.com", "000000", NEW_PASSWORD) }
            }

            val error = assertFailsWith<AuthException> { recovery().resetPassword("reset@example.com", "123456", NEW_PASSWORD) }
            assertEquals("INVALID_RESET_CODE", error.code)
        }

    @Test
    fun anExpiredCodeIsRefused() =
        runBlocking<Unit> {
            signUp()
            recovery().requestReset("reset@example.com", null)
            val later = Clock.fixed(Instant.now().plus(PasswordRecoveryService.CODE_LIFETIME).plusSeconds(60), ZoneOffset.UTC)

            assertFailsWith<AuthException> { recovery(clock = later).resetPassword("reset@example.com", "123456", NEW_PASSWORD) }
        }

    @Test
    fun withoutAnEmailProviderResetSaysItIsUnavailable() =
        runBlocking<Unit> {
            signUp()
            val error =
                assertFailsWith<AuthException> {
                    recovery(resetMailer = UnconfiguredPasswordResetMailer).requestReset("reset@example.com", null)
                }
            assertEquals("PASSWORD_RESET_UNAVAILABLE", error.code)
        }

    @Test
    fun changingPasswordKeepsThisDeviceAndSignsOutTheOthers() =
        runBlocking<Unit> {
            val here = signUp()
            val elsewhere = signIn(OLD_PASSWORD)
            val userId = UUID.fromString(here.user.userId)

            val wrong =
                assertFailsWith<AuthException> {
                    recovery().changePassword(userId, here.accessToken, "not-my-password!", NEW_PASSWORD)
                }
            assertEquals("WRONG_CURRENT_PASSWORD", wrong.code)

            recovery().changePassword(userId, here.accessToken, OLD_PASSWORD, NEW_PASSWORD)

            authService.invalidateCachedSessionsForUser(userId)
            assertEquals(userId, authService.authenticate(here.accessToken).id)
            assertFailsWith<AuthException> { authService.authenticate(elsewhere.accessToken) }
            signIn(NEW_PASSWORD)
            assertNull(mailer.sent.firstOrNull())
        }

    private class CapturingMailer : PasswordResetMailer {
        val sent = mutableListOf<Pair<String, String>>()
        override val isConfigured = true

        override suspend fun sendResetCode(
            email: String,
            code: String,
        ) {
            sent += email to code
        }
    }

    private companion object {
        const val OLD_PASSWORD = "old-password-123"
        const val NEW_PASSWORD = "new-password-456"
    }
}
