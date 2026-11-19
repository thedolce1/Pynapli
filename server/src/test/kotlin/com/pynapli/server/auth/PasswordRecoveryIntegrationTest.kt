package com.pynapli.server.auth

import com.pynapli.server.auth.security.PasswordHash
import com.pynapli.server.auth.security.PasswordHasher
import com.pynapli.server.plugins.configureSerialization
import com.pynapli.server.plugins.configureStatusPages
import com.pynapli.server.safety.RateLimitService
import com.pynapli.server.testsupport.CountingRateLimitRepository
import com.pynapli.server.testsupport.PynapliTestDatabase
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
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

    // Work a reset request hands off to run after its response; tests run it with runResetWork().
    private val pendingResetWork = mutableListOf<suspend () -> Unit>()

    private fun recovery(
        clock: Clock = Clock.systemUTC(),
        resetMailer: PasswordResetMailer = mailer,
        repository: PasswordRecoveryRepository = JdbcPasswordRecoveryRepository(dataSource),
        rateLimitService: RateLimitService? = null,
    ) = PasswordRecoveryService(
        repository = repository,
        mailer = resetMailer,
        launchResetWork = { pendingResetWork += it },
        passwordHasher = hasher,
        clock = clock,
        rateLimitService = rateLimitService,
        onSessionsRevoked = { revoked += it },
        codeGenerator = { nextCode },
    )

    private suspend fun runResetWork() {
        while (pendingResetWork.isNotEmpty()) pendingResetWork.removeAt(0).invoke()
    }

    /** A reset request followed by the work it hands off, as the server's background scope would run it. */
    private suspend fun requestReset(email: String) {
        recovery().requestReset(email, deviceId = null)
        runResetWork()
    }

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
            requestReset(" Reset@Example.com ")

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
            requestReset("reset@example.com")
            recovery().resetPassword("reset@example.com", "123456", NEW_PASSWORD)

            val error = assertFailsWith<AuthException> { recovery().resetPassword("reset@example.com", "123456", "another-password-1") }
            assertEquals("INVALID_RESET_CODE", error.code)
        }

    @Test
    fun anUnknownEmailLooksTheSameButSendsNothing() =
        runBlocking<Unit> {
            requestReset("nobody@example.com")

            assertTrue(mailer.sent.isEmpty())
        }

    @Test
    fun aNewCodeRetiresTheOldOne() =
        runBlocking<Unit> {
            signUp()
            requestReset("reset@example.com")
            nextCode = "654321"
            requestReset("reset@example.com")

            assertFailsWith<AuthException> { recovery().resetPassword("reset@example.com", "123456", NEW_PASSWORD) }
            recovery().resetPassword("reset@example.com", "654321", NEW_PASSWORD)
            signIn(NEW_PASSWORD)
        }

    @Test
    fun fiveWrongGuessesKillTheCode() =
        runBlocking<Unit> {
            signUp()
            requestReset("reset@example.com")
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
            requestReset("reset@example.com")
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
    fun aResetRequestTouchesNoAccountBeforeItAnswersMemberOrNot() =
        runBlocking<Unit> {
            signUp()
            val repository = RecordingRecoveryRepository(JdbcPasswordRecoveryRepository(dataSource))

            recovery(repository = repository).requestReset("reset@example.com", null)
            recovery(repository = repository).requestReset("nobody@example.com", null)

            // Both have answered: nothing has been looked up, written or sent for either.
            assertEquals(emptyList<String>(), repository.calls)
            assertTrue(mailer.sent.isEmpty())
            assertEquals(2, pendingResetWork.size)

            runResetWork()

            // Afterwards the member gets a code and an email; the stranger gets a lookup and nothing else.
            assertEquals(listOf("findEmailIdentity", "replaceResetCode", "findEmailIdentity"), repository.calls)
            assertEquals(listOf("reset@example.com" to "123456"), mailer.sent)
        }

    @Test
    fun aResetRequestAnswersIdenticallyOverHttpForAMemberAndAStranger() =
        testApplication {
            signUp()
            val recoveryService = recovery()
            application {
                configureSerialization()
                configureStatusPages()
                configureAuthRoutes(authService, passwordRecoveryService = recoveryService)
            }

            suspend fun request(email: String): HttpResponse =
                client.post("/v1/auth/password/reset-request") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"email":"$email"}""")
                }

            val member = request("reset@example.com")
            val stranger = request("nobody@example.com")

            assertEquals(HttpStatusCode.OK, member.status)
            assertEquals(member.status, stranger.status)
            assertEquals(member.bodyAsText(), stranger.bodyAsText())
            assertEquals(member.comparableHeaders(), stranger.comparableHeaders())
            assertTrue(mailer.sent.isEmpty(), "nothing is sent before the response")

            runResetWork()
            assertEquals(listOf("reset@example.com" to "123456"), mailer.sent)
        }

    @Test
    fun aFailedSendNoLongerChangesTheAnswerForAMember() =
        runBlocking<Unit> {
            signUp()

            // Before, a member whose email failed got PASSWORD_RESET_UNAVAILABLE while a stranger
            // got "sent": an oracle. Now the request has answered before the send is tried, and the
            // failure stays in the background work (logged, never thrown).
            val failing = FailingMailer()
            recovery(resetMailer = failing).requestReset("reset@example.com", null)
            recovery(resetMailer = failing).requestReset("nobody@example.com", null)
            assertEquals(0, failing.attempts)

            runResetWork()
            assertEquals(1, failing.attempts, "only the member's send is tried")
        }

    @Test
    fun theRateLimitTreatsAMemberAndAStrangerAlike() =
        runBlocking<Unit> {
            signUp()
            val limits = RateLimitService(CountingRateLimitRepository())

            for (email in listOf("reset@example.com", "nobody@example.com")) {
                repeat(RESET_REQUESTS_PER_HOUR) { recovery(rateLimitService = limits).requestReset(email, null) }
                val error = assertFailsWith<AuthException> { recovery(rateLimitService = limits).requestReset(email, null) }
                assertEquals("RATE_LIMITED", error.code, email)
            }
            // Only allowed requests hand off work: one per allowed request, member or not.
            assertEquals(RESET_REQUESTS_PER_HOUR * 2, pendingResetWork.size)
        }

    @Test
    fun aWrongCodeDoesTheSameWorkForAMemberAStrangerAndAnAccountWithoutACode() =
        runBlocking<Unit> {
            signUp()
            signUp(email = "nocode@example.com")
            requestReset("reset@example.com")
            val repository = RecordingRecoveryRepository(JdbcPasswordRecoveryRepository(dataSource))
            val expected = listOf("findEmailIdentity", "findLiveResetCode", "recordFailedAttempt")

            for (email in listOf("reset@example.com", "nobody@example.com", "nocode@example.com")) {
                repository.calls.clear()
                val error = assertFailsWith<AuthException> { recovery(repository = repository).resetPassword(email, "000000", NEW_PASSWORD) }
                assertEquals("INVALID_RESET_CODE", error.code, email)
                assertEquals(expected, repository.calls, email)
            }
            // The member's real code still works after one wrong try.
            recovery().resetPassword("reset@example.com", "123456", NEW_PASSWORD)
            signIn(NEW_PASSWORD)
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

    private fun HttpResponse.comparableHeaders(): Map<String, List<String>> =
        headers
            .entries()
            .filterNot { it.key.equals(HttpHeaders.Date, ignoreCase = true) }
            .associate { it.key.lowercase() to it.value }

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

    private class FailingMailer : PasswordResetMailer {
        var attempts = 0
        override val isConfigured = true

        override suspend fun sendResetCode(
            email: String,
            code: String,
        ) {
            attempts += 1
            throw IllegalStateException("provider down")
        }
    }

    /** Records which repository calls a flow makes, in order, and passes them through. */
    private class RecordingRecoveryRepository(
        private val delegate: PasswordRecoveryRepository,
    ) : PasswordRecoveryRepository {
        val calls = mutableListOf<String>()

        override suspend fun findEmailIdentity(emailHash: String): EmailIdentityRecord? {
            calls += "findEmailIdentity"
            return delegate.findEmailIdentity(emailHash)
        }

        override suspend fun findPasswordHash(userId: UUID): PasswordHash? {
            calls += "findPasswordHash"
            return delegate.findPasswordHash(userId)
        }

        override suspend fun replaceResetCode(
            codeId: UUID,
            userId: UUID,
            codeHash: String,
            expiresAt: Instant,
        ) {
            calls += "replaceResetCode"
            delegate.replaceResetCode(codeId, userId, codeHash, expiresAt)
        }

        override suspend fun findLiveResetCode(
            userId: UUID,
            now: Instant,
        ): ResetCodeRecord? {
            calls += "findLiveResetCode"
            return delegate.findLiveResetCode(userId, now)
        }

        override suspend fun recordFailedAttempt(codeId: UUID) {
            calls += "recordFailedAttempt"
            delegate.recordFailedAttempt(codeId)
        }

        override suspend fun completeReset(
            codeId: UUID,
            userId: UUID,
            passwordHash: PasswordHash,
        ): Boolean {
            calls += "completeReset"
            return delegate.completeReset(codeId, userId, passwordHash)
        }

        override suspend fun changePassword(
            userId: UUID,
            passwordHash: PasswordHash,
            keepAccessTokenHash: String,
        ) {
            calls += "changePassword"
            delegate.changePassword(userId, passwordHash, keepAccessTokenHash)
        }
    }

    private companion object {
        const val OLD_PASSWORD = "old-password-123"
        const val NEW_PASSWORD = "new-password-456"

        // RateLimitService's AUTH_PASSWORD_RESET budget.
        const val RESET_REQUESTS_PER_HOUR = 5
    }
}
