package com.pynapli.server.auth

import com.pynapli.server.auth.security.PasswordHash
import com.pynapli.server.auth.security.PasswordHasher
import com.pynapli.server.auth.security.Sha256
import com.pynapli.server.dependencies.devSignupShortcutsAllowed
import com.pynapli.server.plugins.configureSerialization
import com.pynapli.server.plugins.configureStatusPages
import com.pynapli.server.safety.RateLimitService
import com.pynapli.server.testsupport.CapturingSignupMailer
import com.pynapli.server.testsupport.CountingRateLimitRepository
import com.pynapli.server.testsupport.PynapliTestDatabase
import com.pynapli.server.testsupport.QueuedSignupWork
import com.pynapli.server.testsupport.TEST_CLIENT_IP
import com.pynapli.server.testsupport.raceTogether
import com.pynapli.server.testsupport.signUpVerified
import com.pynapli.server.testsupport.testServerConfig
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Collections
import java.util.UUID
import kotlin.io.path.createTempFile
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Email sign-up that verifies the address and never reveals membership (ONBOARDING_AUTH_SPEC,
 * "Membership privacy"). Work is asserted, never wall-clock time: the off-request work is queued
 * ([QueuedSignupWork]) so each test sees what the request did by itself before running it.
 */
class EmailSignupIntegrationTest {
    private val dataSource = PynapliTestDatabase.dataSource
    private val hasher = PasswordHasher(iterations = 1_000)
    private val authService = AuthService(JdbcAuthRepository(dataSource), passwordHasher = hasher)
    private val mailer = CapturingSignupMailer()
    private val work = QueuedSignupWork()
    private val repository = CountingSignupRepository(JdbcEmailSignupRepository(dataSource))
    private var nextCode = "123456"
    private var nextToken = "token-1"

    private fun signup(
        clock: Clock = Clock.systemUTC(),
        signupMailer: SignupMailer = mailer,
        rateLimitService: RateLimitService? = null,
    ) = EmailSignupService(
        repository = repository,
        mailer = signupMailer,
        authService = authService,
        passwordHasher = hasher,
        clock = clock,
        rateLimitService = rateLimitService,
        launchSignupWork = work::launch,
        codeGenerator = { nextCode },
        tokenGenerator = { nextToken },
    )

    @BeforeTest
    fun reset() {
        PynapliTestDatabase.reset()
    }

    private suspend fun EmailSignupService.start(
        email: String,
        password: String = PASSWORD,
        deviceId: String? = "phone",
    ) = startSignup(email, password, "1990-01-01", true, true, true, deviceId = deviceId, clientIp = TEST_CLIENT_IP)

    private suspend fun EmailSignupService.verifyWith(
        email: String,
        code: String,
        token: String,
    ) = verify(email, code, token, deviceId = "phone", platform = "android", appVersion = "1.0", clientIp = TEST_CLIENT_IP)

    private suspend fun member(email: String = MEMBER) = authService.signUpVerified(email, PASSWORD, platform = "android", appVersion = "1.0")

    @Test
    fun aNewAndARegisteredAddressGetTheSameAnswerAfterTheSameRequestWork() =
        testApplication {
            runBlocking { member() }
            val service = signup()
            application {
                configureSerialization()
                configureStatusPages()
                configureEmailSignupRoutes(service)
            }
            repository.calls.clear()

            val forMember = client.post("/v1/auth/email/signup") { json(signupBody(MEMBER, "a-different-password-1")) }
            val callsAfterMember = repository.calls.toList()
            val queuedAfterMember = work.size
            val forNewcomer = client.post("/v1/auth/email/signup") { json(signupBody(NEWCOMER, PASSWORD)) }

            assertEquals(HttpStatusCode.Accepted, forMember.status)
            assertEquals(forMember.status, forNewcomer.status)
            assertEquals(forMember.bodyAsText(), forNewcomer.bodyAsText())
            assertEquals(headersOf(forMember), headersOf(forNewcomer))
            // On the request: no lookup and no write of any kind, for either address; one queued job each.
            assertEquals(emptyList(), callsAfterMember)
            assertEquals(emptyList(), repository.calls)
            assertEquals(1, queuedAfterMember)
            assertEquals(2, work.size)
            assertTrue(mailer.codes.isEmpty() && mailer.notices.isEmpty())

            runBlocking { work.runAll() }

            assertEquals(listOf(MEMBER), mailer.notices)
            assertEquals(listOf(NEWCOMER to "123456"), mailer.codes)
            assertEquals(1, count("SELECT count(*) FROM pending_email_signups"))
            assertEquals(1, count("SELECT count(*) FROM pending_email_signups WHERE email_hash = ?", Sha256.hash(NEWCOMER)))
            // The member's account is untouched: still one, same password.
            assertEquals(1, count("SELECT count(*) FROM users"))
            runBlocking { authService.loginEmail(MEMBER, PASSWORD, null, "android", "1.0") }
            assertFailsWith<AuthException> { runBlocking { authService.loginEmail(MEMBER, "a-different-password-1", null, "android", "1.0") } }
        }

    @Test
    fun verifyCreatesExactlyTheRowsSignUpAlwaysCreatedAndAWorkingSession() =
        runBlocking<Unit> {
            val service = signup()
            val started = service.start(NEWCOMER)
            work.runAll()
            val before = tableCounts()

            val session = service.verifyWith(NEWCOMER, "123456", started.signupToken)

            val after = tableCounts()
            val changed = (before.keys + after.keys).associateWith { (after[it] ?: 0) - (before[it] ?: 0) }.filterValues { it != 0L }
            // Today's sign-up rows (JdbcAuthUserActions.insertEmailAccount + the session), and the pending row gone.
            assertEquals(
                mapOf(
                    "users" to 1L,
                    "auth_identities" to 1L,
                    "profiles" to 1L,
                    "discovery_settings" to 1L,
                    "user_sessions" to 1L,
                    "pending_email_signups" to -1L,
                ),
                changed,
            )
            val userId = UUID.fromString(session.user.userId)
            assertEquals(userId, authService.authenticate(session.accessToken).id)
            assertEquals("active", session.user.status)
            assertEquals(1, count("SELECT count(*) FROM users WHERE id = ? AND email_verified_at IS NOT NULL AND email_hash = ? AND terms_version = ?", userId, Sha256.hash(NEWCOMER), AUTH_TERMS_VERSION))
            assertEquals(1, count("SELECT count(*) FROM profiles WHERE user_id = ? AND birthdate = DATE '1990-01-01' AND profile_completeness_score = 25", userId))
            authService.loginEmail(NEWCOMER, PASSWORD, null, "android", "1.0")
        }

    @Test
    fun everyFailureIsTheSameErrorAfterTheSameWork() =
        runBlocking<Unit> {
            member()
            val service = signup()
            val started = service.start(NEWCOMER)
            work.runAll()

            val attempts =
                listOf(
                    "wrong code" to suspend { service.verifyWith(NEWCOMER, "000000", started.signupToken) },
                    "unknown address" to suspend { service.verifyWith("nobody@example.com", "123456", started.signupToken) },
                    "registered address" to suspend { service.verifyWith(MEMBER, "123456", started.signupToken) },
                    "wrong token" to suspend { service.verifyWith(NEWCOMER, "123456", "someone-elses-token") },
                    "expired code" to
                        suspend {
                            signup(clock = Clock.fixed(Instant.now().plus(EmailSignupService.CODE_LIFETIME).plusSeconds(60), ZoneOffset.UTC))
                                .verifyWith(NEWCOMER, "123456", started.signupToken)
                        },
                )
            attempts.forEach { (name, attempt) ->
                repository.calls.clear()
                val error = assertFailsWith<AuthException>(name) { attempt() }
                assertEquals("INVALID_SIGNUP_CODE", error.code, name)
                assertEquals(listOf("findLivePendingSignup", "recordFailedAttempt"), repository.calls, name)
            }

            // Already verified: the code worked once and the same request now fails the same way.
            service.verifyWith(NEWCOMER, "123456", started.signupToken)
            repository.calls.clear()
            val again = assertFailsWith<AuthException> { service.verifyWith(NEWCOMER, "123456", started.signupToken) }
            assertEquals("INVALID_SIGNUP_CODE", again.code)
            assertEquals(listOf("findLivePendingSignup", "recordFailedAttempt"), repository.calls)
        }

    @Test
    fun aReplacedCodeAndAnExpiredSignUpFail() =
        runBlocking<Unit> {
            val service = signup()
            val first = service.start(NEWCOMER)
            work.runAll()
            nextCode = "654321"
            nextToken = "token-2"
            val second = service.start(NEWCOMER)
            work.runAll()

            assertEquals(1, count("SELECT count(*) FROM pending_email_signups"))
            assertFailsWith<AuthException> { service.verifyWith(NEWCOMER, "123456", first.signupToken) }
            assertFailsWith<AuthException> { service.verifyWith(NEWCOMER, "654321", first.signupToken) }
            val afterADay = signup(clock = Clock.fixed(Instant.now().plus(EmailSignupService.PENDING_LIFETIME).plusSeconds(60), ZoneOffset.UTC))
            assertFailsWith<AuthException> { afterADay.verifyWith(NEWCOMER, "654321", second.signupToken) }

            service.verifyWith(NEWCOMER, "654321", second.signupToken)
        }

    @Test
    fun aResendAfterTheCodeExpiredIssuesAFreshOneForTheSameSignUp() =
        runBlocking<Unit> {
            val started = signup().start(NEWCOMER)
            work.runAll()
            val later = signup(clock = Clock.fixed(Instant.now().plus(EmailSignupService.CODE_LIFETIME).plusSeconds(60), ZoneOffset.UTC))
            assertFailsWith<AuthException> { later.verifyWith(NEWCOMER, "123456", started.signupToken) }

            nextCode = "222333"
            later.resendCode(NEWCOMER, started.signupToken, deviceId = "phone", clientIp = TEST_CLIENT_IP)
            work.runAll()

            assertEquals(NEWCOMER to "222333", mailer.codes.last())
            later.verifyWith(NEWCOMER, "222333", started.signupToken)
        }

    @Test
    fun theAttemptCapHoldsButOnlyGuessesWithTheRightTokenCount() =
        runBlocking<Unit> {
            val service = signup()
            val started = service.start(NEWCOMER)
            work.runAll()

            // Someone who only knows the address can't use up the owner's attempts.
            repeat(EmailSignupService.MAX_FAILED_ATTEMPTS * 2) {
                assertFailsWith<AuthException> { service.verifyWith(NEWCOMER, "000000", "not-the-token") }
            }
            assertEquals(0, count("SELECT failed_attempts FROM pending_email_signups"))

            repeat(EmailSignupService.MAX_FAILED_ATTEMPTS) {
                assertFailsWith<AuthException> { service.verifyWith(NEWCOMER, "000000", started.signupToken) }
            }
            val dead = assertFailsWith<AuthException> { service.verifyWith(NEWCOMER, "123456", started.signupToken) }
            assertEquals("INVALID_SIGNUP_CODE", dead.code)

            // The owner is never stuck: a resend gives a fresh code with fresh attempts.
            nextCode = "777888"
            service.resendCode(NEWCOMER, started.signupToken, deviceId = "phone", clientIp = TEST_CLIENT_IP)
            work.runAll()
            service.verifyWith(NEWCOMER, "777888", started.signupToken)
        }

    @Test
    fun theSilentSendCapsHoldTheSameWayForMembersAndNonMembers() =
        runBlocking<Unit> {
            member()
            val limits = RateLimitService(CountingRateLimitRepository())
            val service = signup(rateLimitService = limits)

            val answers =
                // No device id: this is about the per-address caps, not the per-device limit.
                (1..7).flatMap {
                    listOf(service.start(NEWCOMER, deviceId = null), service.start(MEMBER, deviceId = null))
                }
            work.runAll()

            assertEquals(1, answers.map { it.copy(signupToken = "") }.toSet().size, "every answer has the same shape")
            assertEquals(HOURLY_SEND_CAP, mailer.codes.size)
            assertEquals(HOURLY_SEND_CAP, mailer.notices.size)
        }

    @Test
    fun aPendingSignUpCannotSignIn() =
        runBlocking<Unit> {
            signup().start(NEWCOMER)
            work.runAll()

            val error = assertFailsWith<AuthException> { authService.loginEmail(NEWCOMER, PASSWORD, null, "android", "1.0") }
            assertEquals("INVALID_CREDENTIALS", error.code)
        }

    @Test
    fun twoConcurrentVerifiesCreateOneAccount() =
        runBlocking<Unit> {
            val service = signup()
            val started = service.start(NEWCOMER)
            work.runAll()

            val (first, second) =
                raceTogether(
                    { service.verifyWith(NEWCOMER, "123456", started.signupToken) },
                    { service.verifyWith(NEWCOMER, "123456", started.signupToken) },
                )

            assertEquals(1, listOf(first, second).count { it.isSuccess })
            val loser = listOf(first, second).single { it.isFailure }.exceptionOrNull()
            assertEquals("INVALID_SIGNUP_CODE", (loser as AuthException).code)
            assertEquals(1, count("SELECT count(*) FROM users"))
            assertEquals(0, count("SELECT count(*) FROM pending_email_signups"))
        }

    @Test
    fun concurrentSignUpsForOneAddressLeaveOnePendingRow() =
        runBlocking<Unit> {
            val service = signup()
            service.start(NEWCOMER)
            nextToken = "token-2"
            service.start(NEWCOMER)
            // Run the two queued jobs against each other rather than one after the other.
            val (first, second) = work.drain()
            val (a, b) = raceTogether(first, second)

            assertTrue(a.isSuccess && b.isSuccess)
            assertEquals(2, mailer.codes.size)

            assertEquals(1, count("SELECT count(*) FROM pending_email_signups WHERE email_hash = ?", Sha256.hash(NEWCOMER)))
        }

    @Test
    fun resendIsTheSameAnswerAndOnlyTheTokenHolderGetsACode() =
        runBlocking<Unit> {
            member()
            val service = signup()
            val started = service.start(NEWCOMER)
            work.runAll()
            mailer.codes.clear()
            repository.calls.clear()

            service.resendCode("nobody@example.com", started.signupToken, null, TEST_CLIENT_IP)
            service.resendCode(MEMBER, started.signupToken, null, TEST_CLIENT_IP)
            service.resendCode(NEWCOMER, "not-the-token", null, TEST_CLIENT_IP)
            nextCode = "999000"
            service.resendCode(NEWCOMER, started.signupToken, null, TEST_CLIENT_IP)
            assertEquals(emptyList(), repository.calls, "nothing is looked up on the request")
            assertEquals(4, work.size)
            work.runAll()

            assertEquals(listOf(NEWCOMER to "999000"), mailer.codes)
            assertTrue(mailer.notices.isEmpty())
        }

    @Test
    fun withoutAMailerSignUpFailsClosedForEveryAddressAlike() =
        runBlocking<Unit> {
            member()
            val service = signup(signupMailer = CapturingSignupMailer(isConfigured = false))

            listOf(NEWCOMER, MEMBER).forEach { email ->
                val error = assertFailsWith<AuthException> { service.start(email) }
                assertEquals("SIGNUP_UNAVAILABLE", error.code)
                val resend = assertFailsWith<AuthException> { service.resendCode(email, "token", null, TEST_CLIENT_IP) }
                assertEquals("SIGNUP_UNAVAILABLE", resend.code)
            }
            assertEquals(0, work.size)
            assertEquals(0, count("SELECT count(*) FROM pending_email_signups"))
        }

    @Test
    fun aBannedAccountsAddressGetsTheNoticeAndADeletedOnesCanSignUpAgain() =
        runBlocking<Unit> {
            val banned = member("banned@example.com")
            execute("UPDATE users SET status = 'banned' WHERE id = ?", UUID.fromString(banned.user.userId))
            val deleted = member("deleted@example.com")
            // What account deletion does to the identity (JdbcAccountDeletionActions).
            execute("UPDATE users SET email_hash = NULL, status = 'deleted', deleted_at = now() WHERE id = ?", UUID.fromString(deleted.user.userId))
            execute("UPDATE auth_identities SET provider_subject = 'deleted:' || id WHERE user_id = ?", UUID.fromString(deleted.user.userId))
            val service = signup()

            service.start("banned@example.com")
            val again = service.start("deleted@example.com")
            work.runAll()

            assertEquals(listOf("banned@example.com"), mailer.notices)
            assertEquals(listOf("deleted@example.com" to "123456"), mailer.codes)
            service.verifyWith("deleted@example.com", "123456", again.signupToken)
        }

    @Test
    fun theDevShortcutsExistOnlyWhenWiredAndMakeTheSameAccount() =
        testApplication {
            val service = signup()
            val mailbox = LocalFileSignupMailer(createTempFile("signup-codes", ".txt").toFile())
            application {
                configureSerialization()
                configureStatusPages()
                configureEmailSignupRoutes(service, devMailbox = mailbox)
            }

            val created = client.post("/v1/dev/auth/email/signup") { json(signupBody(NEWCOMER, PASSWORD)) }
            assertEquals(HttpStatusCode.OK, created.status)
            assertTrue(created.bodyAsText().contains("accessToken"))
            assertEquals(1, count("SELECT count(*) FROM users WHERE email_verified_at IS NOT NULL"))
            val again = client.post("/v1/dev/auth/email/signup") { json(signupBody(NEWCOMER, PASSWORD)) }
            assertEquals(HttpStatusCode.Conflict, again.status)

            runBlocking { mailbox.sendSignupCode("Someone@Example.com", "424242") }
            val code = client.post("/v1/dev/auth/email/signup/latest-code") { json("""{"email":"someone@example.com"}""") }
            assertTrue(code.bodyAsText().contains("424242"))
        }

    @Test
    fun theDevShortcutsAreAbsentWithoutTheMailbox() =
        testApplication {
            application {
                configureSerialization()
                configureStatusPages()
                configureEmailSignupRoutes(signup())
            }

            assertEquals(HttpStatusCode.NotFound, client.post("/v1/dev/auth/email/signup") { json(signupBody(NEWCOMER, PASSWORD)) }.status)
            assertEquals(HttpStatusCode.NotFound, client.post("/v1/dev/auth/email/signup/latest-code") { json("""{"email":"a@example.com"}""") }.status)
        }

    @Test
    fun theDevShortcutsNeedTheFlagALocalEnvironmentAndTheLocalMailer() {
        val local = testServerConfig(environment = "local").copy(devSignupShortcutsEnabled = true, passwordResetMailer = "log")
        assertTrue(local.devSignupShortcutsAllowed())
        assertFalse(local.copy(devSignupShortcutsEnabled = false).devSignupShortcutsAllowed())
        assertFalse(local.copy(environment = "test").devSignupShortcutsAllowed())
        assertFalse(local.copy(environment = "staging").devSignupShortcutsAllowed())
        assertFalse(local.copy(passwordResetMailer = "none").devSignupShortcutsAllowed())
    }

    private fun HttpRequestBuilder.json(body: String) {
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private fun signupBody(
        email: String,
        password: String,
    ) = """{"email":"$email","password":"$password","birthdate":"1990-01-01","confirmedAdult":true,"acceptedTerms":true,"acceptedPrivacy":true,"deviceId":"phone","platform":"android","appVersion":"1.0"}"""

    // Date is the only header allowed to differ (it is the clock, not the account).
    private fun headersOf(response: HttpResponse): Map<String, List<String>> =
        response.headers
            .entries()
            .filterNot { it.key.equals("Date", ignoreCase = true) }
            .associate { it.key.lowercase() to it.value }

    private fun tableCounts(): Map<String, Long> =
        dataSource.connection.use { connection ->
            val tables =
                connection.prepareStatement("SELECT tablename FROM pg_tables WHERE schemaname = 'public' AND tablename NOT IN ('flyway_schema_history', 'spatial_ref_sys')").use { statement ->
                    statement.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
                }
            tables.associateWith { table ->
                connection.prepareStatement("SELECT count(*) FROM \"$table\"").use { statement ->
                    statement.executeQuery().use { rs ->
                        rs.next()
                        rs.getLong(1)
                    }
                }
            }
        }

    private fun count(
        sql: String,
        vararg args: Any,
    ): Int =
        dataSource.connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                args.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
                statement.executeQuery().use { rs ->
                    if (rs.next()) rs.getInt(1) else 0
                }
            }
        }

    private fun execute(
        sql: String,
        vararg args: Any,
    ) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                args.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
                statement.executeUpdate()
            }
        }
    }

    private companion object {
        const val PASSWORD = "signup-password-1"
        const val MEMBER = "member@example.com"
        const val NEWCOMER = "newcomer@example.com"
        const val HOURLY_SEND_CAP = 5
    }
}

/** Records which repository calls ran, in order, so tests can compare the work two requests did. */
private class CountingSignupRepository(
    private val inner: EmailSignupRepository,
) : EmailSignupRepository {
    val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())

    override suspend fun findEmailIdentity(emailHash: String): EmailIdentityRecord? {
        calls += "findEmailIdentity"
        return inner.findEmailIdentity(emailHash)
    }

    override suspend fun replacePendingSignup(
        pending: NewPendingSignup,
        now: Instant,
    ) {
        calls += "replacePendingSignup"
        inner.replacePendingSignup(pending, now)
    }

    override suspend fun findLivePendingSignup(
        emailHash: String,
        now: Instant,
    ): PendingSignupRecord? {
        calls += "findLivePendingSignup"
        return inner.findLivePendingSignup(emailHash, now)
    }

    override suspend fun replaceCode(
        pendingId: UUID,
        codeHash: String,
        codeExpiresAt: Instant,
    ): Boolean {
        calls += "replaceCode"
        return inner.replaceCode(pendingId, codeHash, codeExpiresAt)
    }

    override suspend fun recordFailedAttempt(pendingId: UUID) {
        calls += "recordFailedAttempt"
        inner.recordFailedAttempt(pendingId)
    }

    override suspend fun completeSignup(
        pendingId: UUID,
        expectedCodeHash: String,
        userId: UUID,
        identityId: UUID,
        displayAge: Int,
        verifiedAt: Instant,
    ): UserRecord? {
        calls += "completeSignup"
        return inner.completeSignup(pendingId, expectedCodeHash, userId, identityId, displayAge, verifiedAt)
    }

    override suspend fun createVerifiedAccount(
        userId: UUID,
        identityId: UUID,
        emailHash: String,
        passwordHash: PasswordHash,
        birthdate: LocalDate,
        displayAge: Int,
        acceptedAt: Instant,
        termsVersion: String,
        privacyVersion: String,
        verifiedAt: Instant,
    ): UserRecord? {
        calls += "createVerifiedAccount"
        return inner.createVerifiedAccount(userId, identityId, emailHash, passwordHash, birthdate, displayAge, acceptedAt, termsVersion, privacyVersion, verifiedAt)
    }
}
