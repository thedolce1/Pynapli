package com.pynapli.server.auth

import com.pynapli.server.auth.security.PasswordHash
import com.pynapli.server.auth.security.PasswordHasher
import com.pynapli.server.auth.security.Sha256
import com.pynapli.server.auth.security.normalizeEmail
import com.pynapli.server.dependencies.devSignupShortcutsAllowed
import com.pynapli.server.plugins.configureSerialization
import com.pynapli.server.plugins.configureStatusPages
import com.pynapli.server.retention.DataRetentionService
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
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.time.Duration
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
 * Email sign-up that verifies the address and never reveals membership (ONBOARDING_AUTH_SPEC, "Email
 * sign-up"). Everything here runs against the real database, since the SQL is the point. Work is
 * asserted, never wall-clock time: the off-request work is queued ([QueuedSignupWork]) so each test
 * sees what the request did by itself before running it.
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
        tokens: () -> String = { nextToken },
    ) = EmailSignupService(
        repository = repository,
        mailer = signupMailer,
        authService = authService,
        passwordHasher = hasher,
        clock = clock,
        rateLimitService = rateLimitService,
        launchSignupWork = work::launch,
        codeGenerator = { nextCode },
        tokenGenerator = tokens,
    )

    @BeforeTest
    fun reset() {
        PynapliTestDatabase.reset()
    }

    private suspend fun EmailSignupService.start(
        email: String,
        password: String = PASSWORD,
        deviceId: String? = "phone",
        clientIp: String = TEST_CLIENT_IP,
    ) = startSignup(email, password, "1990-01-01", true, true, true, deviceId = deviceId, clientIp = clientIp)

    private suspend fun EmailSignupService.verifyWith(
        email: String,
        code: String,
        token: String,
        clientIp: String = TEST_CLIENT_IP,
    ) = verify(email, code, token, deviceId = "phone", platform = "android", appVersion = "1.0", clientIp = clientIp)

    private suspend fun member(email: String = MEMBER) = authService.signUpVerified(email, PASSWORD, platform = "android", appVersion = "1.0")

    private fun later(duration: Duration) = Clock.fixed(Instant.now().plus(duration).plusSeconds(60), ZoneOffset.UTC)

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
            assertEquals(1, pendingRows())
            assertEquals(1, pendingRows(NEWCOMER))
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
            // Puts the placeholder row in place (the test database is truncated between tests).
            assertFailsWith<AuthException> { service.verifyWith("nobody@example.com", "000000", "no-token") }
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
    fun everyFailureWritesExactlyOneRowWhetherOrNotTheAddressIsAMember() =
        runBlocking<Unit> {
            member()
            val service = signup()
            // The attacker's view: a sign-up started FOR the member's address (no row is ever made for
            // it) and one for an address with no account (a real row exists for its token).
            nextToken = "token-for-member"
            val forMember = service.start(MEMBER)
            nextToken = "token-for-newcomer"
            val forNewcomer = service.start(NEWCOMER)
            work.runAll()
            assertEquals(0, pendingRows(MEMBER))
            assertEquals(1, pendingRows(NEWCOMER))

            val attempts =
                listOf(
                    "member address, its own token" to suspend { service.verifyWith(MEMBER, "123456", forMember.signupToken) },
                    "new address, its own token, wrong code" to suspend { service.verifyWith(NEWCOMER, "000000", forNewcomer.signupToken) },
                    "unknown address" to suspend { service.verifyWith("nobody@example.com", "123456", forNewcomer.signupToken) },
                    "new address, wrong token" to suspend { service.verifyWith(NEWCOMER, "123456", "someone-elses-token") },
                    "expired code" to
                        suspend {
                            signup(clock = later(EmailSignupService.CODE_LIFETIME)).verifyWith(NEWCOMER, "123456", forNewcomer.signupToken)
                        },
                )
            attempts.forEach { (name, attempt) ->
                val before = attemptsByRow()
                repository.calls.clear()
                val error = assertFailsWith<AuthException>(name) { attempt() }
                val after = attemptsByRow()

                assertEquals("INVALID_SIGNUP_CODE", error.code, name)
                assertEquals(listOf("consumeAttempt"), repository.calls, name)
                assertEquals(before.keys, after.keys, "$name: no row added or removed")
                val written = after.filter { (id, value) -> value != before[id] }
                assertEquals(1, written.size, "$name: exactly one row written")
                assertEquals(1L, written.values.single() - before.getValue(written.keys.single()), "$name: by one attempt")
            }

            // Already verified: the code worked once; the same request now fails the same way.
            nextToken = "token-2"
            nextCode = "654321"
            val fresh = service.start("fresh@example.com")
            work.runAll()
            service.verifyWith("fresh@example.com", "654321", fresh.signupToken)
            val before = attemptsByRow()
            assertFailsWith<AuthException> { service.verifyWith("fresh@example.com", "654321", fresh.signupToken) }
            assertEquals(1, attemptsByRow().count { (id, value) -> value != before[id] })
        }

    @Test
    fun concurrentWrongGuessesNeverGetPastTheCapAndTheRightCodeThenFails() =
        runBlocking<Unit> {
            val service = signup()
            val started = service.start(NEWCOMER)
            work.runAll()

            val outcomes =
                coroutineScope {
                    (1..GUESSES)
                        .map { index ->
                            async(Dispatchers.IO) {
                                runCatching { service.verifyWith(NEWCOMER, "%06d".format(index), started.signupToken) }
                            }
                        }.awaitAll()
                }

            assertTrue(outcomes.all { it.isFailure })
            // Every guess spends an attempt before anything is compared. Under heavy concurrency a guess
            // can land on a placeholder row instead (consumeAttempt's fallback) and is then compared
            // against the placeholder's code, never the real one, so it can't succeed; the real row's
            // count is therefore at least the cap, and every comparison with the real code was counted.
            val spent = count("SELECT attempts FROM pending_email_signups WHERE email_hash = ?", Sha256.hash(NEWCOMER))
            assertTrue(spent in EmailSignupService.MAX_ATTEMPTS..GUESSES, "attempts spent on the real sign-up: $spent")
            val dead = assertFailsWith<AuthException> { service.verifyWith(NEWCOMER, "123456", started.signupToken) }
            assertEquals("INVALID_SIGNUP_CODE", dead.code)
            assertEquals(0, count("SELECT count(*) FROM users"))
        }

    @Test
    fun theRightCodeInsideTheBudgetStillWorks() =
        runBlocking<Unit> {
            val service = signup()
            val started = service.start(NEWCOMER)
            work.runAll()
            repeat(EmailSignupService.MAX_ATTEMPTS - 1) {
                assertFailsWith<AuthException> { service.verifyWith(NEWCOMER, "000000", started.signupToken) }
            }

            service.verifyWith(NEWCOMER, "123456", started.signupToken)
        }

    @Test
    fun aResendGivesANewCodeButNeverGivesGuessesBack() =
        runBlocking<Unit> {
            val service = signup()
            val started = service.start(NEWCOMER)
            work.runAll()
            repeat(EmailSignupService.MAX_ATTEMPTS) {
                assertFailsWith<AuthException> { service.verifyWith(NEWCOMER, "000000", started.signupToken) }
            }

            nextCode = "777888"
            repository.calls.clear()
            service.resendCode(NEWCOMER, started.signupToken, deviceId = "phone", clientIp = TEST_CLIENT_IP)
            work.runAll()

            // Nothing is sent: a code that could never work would only mislead. The answer was the same.
            assertEquals(listOf(NEWCOMER to "123456"), mailer.codes)
            assertTrue("replaceCode" in repository.calls)
            assertFailsWith<AuthException> { service.verifyWith(NEWCOMER, "777888", started.signupToken) }
            assertFailsWith<AuthException> { service.verifyWith(NEWCOMER, "123456", started.signupToken) }
        }

    @Test
    fun aResendBeforeTheGuessesAreSpentStillWorks() =
        runBlocking<Unit> {
            val service = signup()
            val started = service.start(NEWCOMER)
            work.runAll()
            repeat(EmailSignupService.MAX_ATTEMPTS - 1) {
                assertFailsWith<AuthException> { service.verifyWith(NEWCOMER, "000000", started.signupToken) }
            }

            nextCode = "777888"
            service.resendCode(NEWCOMER, started.signupToken, deviceId = "phone", clientIp = TEST_CLIENT_IP)
            work.runAll()

            assertEquals(NEWCOMER to "777888", mailer.codes.last())
            service.verifyWith(NEWCOMER, "777888", started.signupToken)
        }

    @Test
    fun aSignUpGetsAtMostThreeCodes() =
        runBlocking<Unit> {
            val service = signup()
            val started = service.start(NEWCOMER)
            work.runAll()

            repeat(4) { index ->
                nextCode = "90000$index"
                service.resendCode(NEWCOMER, started.signupToken, deviceId = "phone", clientIp = TEST_CLIENT_IP)
                work.runAll()
            }

            assertEquals(EmailSignupService.MAX_CODES, mailer.codes.size)
            service.verifyWith(NEWCOMER, "900001", started.signupToken)
        }

    @Test
    fun aResendAfterTheCodeExpiredIssuesAFreshOneForTheSameSignUp() =
        runBlocking<Unit> {
            val started = signup().start(NEWCOMER)
            work.runAll()
            val afterExpiry = signup(clock = later(EmailSignupService.CODE_LIFETIME))
            assertFailsWith<AuthException> { afterExpiry.verifyWith(NEWCOMER, "123456", started.signupToken) }

            nextCode = "222333"
            afterExpiry.resendCode(NEWCOMER, started.signupToken, deviceId = "phone", clientIp = TEST_CLIENT_IP)
            work.runAll()

            assertEquals(NEWCOMER to "222333", mailer.codes.last())
            afterExpiry.verifyWith(NEWCOMER, "222333", started.signupToken)
        }

    @Test
    fun anExpiredSignUpCannotBeFinished() =
        runBlocking<Unit> {
            val started = signup().start(NEWCOMER)
            work.runAll()

            assertFailsWith<AuthException> { signup(clock = later(EmailSignupService.PENDING_LIFETIME)).verifyWith(NEWCOMER, "123456", started.signupToken) }
        }

    @Test
    fun someoneElsesSignUpsAndResendsForTheAddressNeverStopTheOwner() =
        runBlocking<Unit> {
            val limits = RateLimitService(CountingRateLimitRepository())
            var tokenNumber = 0
            val service = signup(rateLimitService = limits, tokens = { "token-${tokenNumber++}" })

            // The owner starts first.
            nextCode = "111111"
            val owner = service.start(NEWCOMER, deviceId = null, clientIp = OWNER_IP)
            work.runAll()
            // Then a stranger floods the address from another network: sign-ups and resends.
            nextCode = "999999"
            val stranger = (1..10).map { service.start(NEWCOMER, deviceId = null, clientIp = STRANGER_IP) }
            repeat(10) { service.resendCode(NEWCOMER, stranger.first().signupToken, null, STRANGER_IP) }
            work.runAll()

            // The stranger's network got its few emails and no more; the owner's sign-up is untouched.
            assertEquals(1 + 3, mailer.codes.size)
            service.verifyWith(NEWCOMER, "111111", owner.signupToken, clientIp = OWNER_IP)
            assertEquals(0, pendingRows(NEWCOMER), "verify removes every sign-up of the address")
        }

    @Test
    fun theOwnerCanStillSignUpAfterSomeoneElseFloodedTheAddress() =
        runBlocking<Unit> {
            val limits = RateLimitService(CountingRateLimitRepository())
            var tokenNumber = 0
            val service = signup(rateLimitService = limits, tokens = { "token-${tokenNumber++}" })
            nextCode = "999999"
            val stranger = (1..10).map { service.start(NEWCOMER, deviceId = null, clientIp = STRANGER_IP) }
            repeat(10) { service.resendCode(NEWCOMER, stranger.last().signupToken, null, STRANGER_IP) }
            // +tag variants share the address family's budget, from the same network.
            (1..10).forEach { service.start("newcomer+$it@example.com", deviceId = null, clientIp = STRANGER_IP) }
            work.runAll()

            nextCode = "111111"
            val owner = service.start(NEWCOMER, deviceId = null, clientIp = OWNER_IP)
            work.runAll()

            assertEquals(NEWCOMER to "111111", mailer.codes.last())
            service.verifyWith(NEWCOMER, "111111", owner.signupToken, clientIp = OWNER_IP)
        }

    @Test
    fun aFullAddressRefusesMoreSignUpsAndNeverEvictsAnEarlierOne() =
        runBlocking<Unit> {
            var tokenNumber = 0
            val service = signup(tokens = { "token-${tokenNumber++}" })
            nextCode = "111111"
            val owner = service.start(NEWCOMER, deviceId = null)
            work.runAll()
            nextCode = "999999"
            repeat(EmailSignupService.MAX_LIVE_PER_ADDRESS + 5) { service.start(NEWCOMER, deviceId = null) }
            work.runAll()

            assertEquals(EmailSignupService.MAX_LIVE_PER_ADDRESS, pendingRows(NEWCOMER))
            assertEquals(EmailSignupService.MAX_LIVE_PER_ADDRESS, mailer.codes.size)
            service.verifyWith(NEWCOMER, "111111", owner.signupToken)
        }

    @Test
    fun concurrentSignUpsForOneAddressStayUnderTheCap() =
        runBlocking<Unit> {
            var tokenNumber = 0
            val service = signup(tokens = { synchronized(this) { "token-${tokenNumber++}" } })
            repeat(EmailSignupService.MAX_LIVE_PER_ADDRESS + 5) { service.start(NEWCOMER, deviceId = null) }
            val jobs = work.drain()

            coroutineScope { jobs.map { job -> async(Dispatchers.IO) { job() } }.awaitAll() }

            assertEquals(EmailSignupService.MAX_LIVE_PER_ADDRESS, pendingRows(NEWCOMER))
            assertEquals(EmailSignupService.MAX_LIVE_PER_ADDRESS, mailer.codes.size)
        }

    @Test
    fun aUniqueViolationAtCompletionKeepsThePendingRowAndAnswersTheSameError() =
        runBlocking<Unit> {
            val service = signup()
            val started = service.start(NEWCOMER)
            work.runAll()
            // The address gets an account some other way between sign-up and verify.
            service.createVerifiedAccountForLocalDev(NEWCOMER, "another-password-1", "1990-01-01", true, true, true, null, null, null)

            val error = assertFailsWith<AuthException> { service.verifyWith(NEWCOMER, "123456", started.signupToken) }

            assertEquals("INVALID_SIGNUP_CODE", error.code)
            assertEquals(1, pendingRows(NEWCOMER), "rolled back: the pending row is still there")
            assertEquals(1, count("SELECT count(*) FROM users"))
        }

    @Test
    fun theDevRouteOnAnExistingUnverifiedAccountVerifiesItWithTheRightPasswordOnly() =
        runBlocking<Unit> {
            val verifiedFor = Collections.synchronizedList(mutableListOf<UUID>())
            val service =
                EmailSignupService(
                    repository = repository,
                    mailer = mailer,
                    authService = authService,
                    passwordHasher = hasher,
                    launchSignupWork = work::launch,
                    onAccountVerified = { userId, _ -> verifiedFor += userId },
                )
            service.createVerifiedAccountForLocalDev(NEWCOMER, PASSWORD, "1990-01-01", true, true, true, null, null, null)
            // A seed account left unverified on an existing database (made before the route verified).
            dataSource.connection.use { it.createStatement().execute("UPDATE users SET email_verified_at = NULL") }
            verifiedFor.clear()

            val wrong = assertFailsWith<AuthException> { service.createVerifiedAccountForLocalDev(NEWCOMER, "not-the-password-1", "1990-01-01", true, true, true, null, null, null) }
            assertEquals("EMAIL_ALREADY_EXISTS", wrong.code)
            assertEquals(0, count("SELECT count(*) FROM users WHERE email_verified_at IS NOT NULL"), "a wrong password changes nothing")
            assertTrue(verifiedFor.isEmpty())

            val again = assertFailsWith<AuthException> { service.createVerifiedAccountForLocalDev(NEWCOMER, PASSWORD, "1990-01-01", true, true, true, null, null, null) }
            assertEquals("EMAIL_ALREADY_EXISTS", again.code, "still 409, so the seeds then sign in")
            assertEquals(1, count("SELECT count(*) FROM users WHERE email_verified_at IS NOT NULL"))
            assertEquals(1, verifiedFor.size, "the contact token work ran, as at a first verify")
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
            assertEquals(0, pendingRows())
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
            assertEquals(0, pendingRows())
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
            nextToken = "token-2"
            val again = service.start("deleted@example.com")
            work.runAll()

            assertEquals(listOf("banned@example.com"), mailer.notices)
            assertEquals(listOf("deleted@example.com" to "123456"), mailer.codes)
            service.verifyWith("deleted@example.com", "123456", again.signupToken)
        }

    @Test
    fun onlyAStrictSingleAddressCanStartASignUpAndTheMailerGetsItNormalised() =
        runBlocking<Unit> {
            val service = signup()
            val rejected =
                listOf(
                    "a@b",
                    "a b@example.com",
                    "a@example.com,b@example.com",
                    "a@example.com;b@example.com",
                    "<a@example.com>",
                    "\"a\"@example.com",
                    "a@@example.com",
                    "a@example..com",
                    ".a@example.com",
                    "a\n@example.com",
                    "a@exa_mple.com",
                    "a@-example.com",
                    "${"a".repeat(65)}@example.com",
                    "a@${"b".repeat(250)}.com",
                    "Bcc: a@example.com",
                    "a@exam'ple.com",
                    "a`b@example.com",
                )
            rejected.forEach { address ->
                val error = assertFailsWith<AuthException>(address) { service.start(address) }
                assertEquals("INVALID_EMAIL", error.code, address)
            }
            assertEquals(0, work.size)

            val started = service.start("  New.Comer+Join@Example.COM ")
            work.runAll()
            assertEquals(listOf("new.comer+join@example.com" to "123456"), mailer.codes)
            service.verifyWith("new.comer+join@example.com", "123456", started.signupToken)
        }

    @Test
    fun anExistingAccountWhoseAddressFailsTheStrictCheckCanStillSignInAndReset() =
        runBlocking<Unit> {
            val loose = "Old Style <member>@example"
            JdbcAuthRepository(dataSource).createEmailUser(
                userId = UUID.randomUUID(),
                identityId = UUID.randomUUID(),
                emailHash = Sha256.hash(normalizeEmail(loose)),
                passwordHash = hasher.hash(PASSWORD),
                birthdate = LocalDate.of(1990, 1, 1),
                displayAge = 30,
                adultConfirmedAt = Instant.now(),
                termsAcceptedAt = Instant.now(),
                termsVersion = AUTH_TERMS_VERSION,
                privacyAcceptedAt = Instant.now(),
                privacyVersion = AUTH_PRIVACY_VERSION,
            )

            authService.loginEmail(loose, PASSWORD, null, "android", "1.0")
        }

    @Test
    fun theDevShortcutsServeADirectLocalRequestAndNeverSayWhoIsAMember() =
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

            runBlocking {
                mailbox.sendSignupCode("Someone@Example.com", "424242")
                mailbox.sendAlreadyRegisteredNotice("member@example.com")
            }
            val code = client.post("/v1/dev/auth/email/signup/latest-code") { json("""{"email":"someone@example.com"}""") }
            assertTrue(code.bodyAsText().contains("424242"))
            val notice = client.post("/v1/dev/auth/email/signup/latest-code") { json("""{"email":"member@example.com"}""") }
            val nobody = client.post("/v1/dev/auth/email/signup/latest-code") { json("""{"email":"nobody@example.com"}""") }
            assertEquals(nobody.bodyAsText(), notice.bodyAsText(), "the lookup never says an address got the notice")
        }

    @Test
    fun theDevShortcutsAreNotFoundBehindAProxyOrTunnelEvenWhenOn() =
        testApplication {
            val mailbox = LocalFileSignupMailer(createTempFile("signup-codes", ".txt").toFile())
            application {
                configureSerialization()
                configureStatusPages()
                configureEmailSignupRoutes(signup(), devMailbox = mailbox)
            }

            val forwarded =
                listOf(
                    "X-Forwarded-For" to "198.51.100.7",
                    "Forwarded" to "for=198.51.100.7",
                    "Cf-Connecting-IP" to "198.51.100.7",
                    "Cf-Ray" to "8a1b2c3d4e5f-AMS",
                    "X-Real-IP" to "198.51.100.7",
                    "Via" to "1.1 proxy",
                )
            forwarded.forEach { (name, value) ->
                val create =
                    client.post("/v1/dev/auth/email/signup") {
                        header(name, value)
                        json(signupBody(NEWCOMER, PASSWORD))
                    }
                val lookup =
                    client.post("/v1/dev/auth/email/signup/latest-code") {
                        header(name, value)
                        json("""{"email":"someone@example.com"}""")
                    }
                assertEquals(HttpStatusCode.NotFound, create.status, name)
                assertEquals(HttpStatusCode.NotFound, lookup.status, name)
            }
            assertEquals(0, count("SELECT count(*) FROM users"))
        }

    @Test
    fun thePlaceholderPoolLooksLikeRealRowsMatchesNothingAndSurvivesEverySweep() =
        runBlocking<Unit> {
            val service = signup()
            // Puts the pool in place (the test database is truncated between tests), as V118 does.
            assertFailsWith<AuthException> { service.verifyWith("nobody@example.com", "000000", "no-token") }

            val pool =
                dataSource.connection.use { connection ->
                    connection.prepareStatement("SELECT id, email_hash, signup_token_hash, code_hash FROM pending_email_signups WHERE expires_at = 'infinity' ORDER BY id").use { statement ->
                        statement.executeQuery().use { rs ->
                            buildList { while (rs.next()) add(listOf(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4))) }
                        }
                    }
                }
            assertEquals(EmailSignupService.PLACEHOLDER_POOL_SIZE, pool.size)
            val realLength = Sha256.hash("anything").length
            pool.forEachIndexed { n, (id, emailHash, tokenHash, codeHash) ->
                assertEquals(UUID(0L, n.toLong()).toString(), id)
                assertTrue(EmailSignupService.isPlaceholder(UUID.fromString(id)))
                // Same length and alphabet as real values, so comparisons run over the same length.
                assertEquals(Sha256.hash("pynapli-placeholder-email:$n"), emailHash)
                assertEquals(Sha256.hash("pynapli-placeholder-token:$n"), tokenHash)
                assertEquals(Sha256.hash("pynapli-placeholder-code:$n"), codeHash)
                listOf(emailHash, tokenHash, codeHash).forEach { assertEquals(realLength, it.length) }
            }

            // A sign-up, a verify that deletes the address's rows, the on-write sweep and the retention
            // job all leave the whole pool alone.
            val started = service.start(NEWCOMER)
            work.runAll()
            service.verifyWith(NEWCOMER, "123456", started.signupToken)
            DataRetentionService(dataSource).runOnce()
            assertEquals(EmailSignupService.PLACEHOLDER_POOL_SIZE, count("SELECT count(*) FROM pending_email_signups WHERE expires_at = 'infinity'"))
            assertFalse(EmailSignupService.isPlaceholder(UUID.randomUUID()))
        }

    @Test
    fun failedVerifiesWithNoSignUpSpreadOverThePool() =
        runBlocking<Unit> {
            val service = signup()
            repeat(200) { assertFailsWith<AuthException> { service.verifyWith("nobody@example.com", "000000", "no-token-$it") } }

            val touched = count("SELECT count(*) FROM pending_email_signups WHERE expires_at = 'infinity' AND attempts > 0")
            assertEquals(200, count("SELECT coalesce(sum(attempts), 0) FROM pending_email_signups WHERE expires_at = 'infinity'"))
            assertTrue(touched > 16, "200 random picks over 64 rows touch far more than a few ($touched)")
        }

    @Test
    fun anApostropheIsFineInTheLocalPartOnly() =
        runBlocking<Unit> {
            val service = signup()
            val started = service.start("O'Brien@Example.com")
            work.runAll()
            assertEquals(listOf("o'brien@example.com" to "123456"), mailer.codes)
            service.verifyWith("o'brien@example.com", "123456", started.signupToken)

            assertEquals("INVALID_EMAIL", assertFailsWith<AuthException> { service.start("obrien@exam'ple.com") }.code)
        }

    @Test
    fun oneIpv6HostCannotPoseAsManyNetworks() =
        runBlocking<Unit> {
            val limits = RateLimitService(CountingRateLimitRepository())
            var tokenNumber = 0
            val service = signup(rateLimitService = limits, tokens = { "token-${tokenNumber++}" })

            // Ten addresses from one /64: one network's budget (3 an hour), not ten.
            (1..10).forEach { service.start(NEWCOMER, deviceId = null, clientIp = "2001:db8:aa:bb::$it") }
            work.runAll()

            assertEquals(3, mailer.codes.size)
        }

    @Test
    fun onlyALoopbackPeerWithoutForwardingHeadersIsADirectLocalRequest() {
        // An IPv6 zone on a loopback peer is still loopback; it never makes anything else loopback.
        assertTrue(isDirectLoopbackRequest("::1%0", emptySet()))
        assertTrue(isDirectLoopbackRequest("0:0:0:0:0:0:0:1%lo", emptySet()))
        assertTrue(isDirectLoopbackRequest("[::1%1]", emptySet()))
        assertFalse(isDirectLoopbackRequest("fe80::1%lo", emptySet()))
        assertFalse(isDirectLoopbackRequest("2001:db8::1%0", emptySet()))
        assertFalse(isDirectLoopbackRequest("::1%0", setOf("X-Forwarded-For")))
        assertFalse(isDirectLoopbackRequest("127.evil.example", emptySet()))
        assertTrue(isDirectLoopbackRequest("127.0.0.1", setOf("Content-Type", "Accept")))
        assertTrue(isDirectLoopbackRequest("0:0:0:0:0:0:0:1", emptySet()))
        assertTrue(isDirectLoopbackRequest("::1", emptySet()))
        assertFalse(isDirectLoopbackRequest("203.0.113.5", emptySet()))
        assertFalse(isDirectLoopbackRequest("10.0.0.2", emptySet()))
        assertFalse(isDirectLoopbackRequest("192.168.1.20", emptySet()))
        // cloudflared and any reverse proxy run on this machine too: their peer IS loopback.
        assertFalse(isDirectLoopbackRequest("127.0.0.1", setOf("X-Forwarded-For")))
        assertFalse(isDirectLoopbackRequest("127.0.0.1", setOf("cf-connecting-ip")))
        assertFalse(isDirectLoopbackRequest("127.0.0.1", setOf("Forwarded")))
        assertFalse(isDirectLoopbackRequest("::1", setOf("X-Forwarded-Proto")))
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
    fun theDevShortcutsAreOffByDefaultAndNeedALocalEnvironmentAndTheLocalMailer() {
        assertFalse(testServerConfig(environment = "local").devSignupShortcutsEnabled, "off unless asked for")
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

    /** Every pending row's attempt count, the placeholder included. */
    private fun attemptsByRow(): Map<UUID, Long> =
        dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT id, attempts FROM pending_email_signups").use { statement ->
                statement.executeQuery().use { rs ->
                    buildMap { while (rs.next()) put(rs.getObject("id", UUID::class.java), rs.getLong("attempts")) }
                }
            }
        }

    /** Real pending sign-ups (never the placeholder), for one address or all. */
    private fun pendingRows(email: String? = null): Int =
        if (email == null) {
            count("SELECT count(*) FROM pending_email_signups WHERE expires_at <> 'infinity'")
        } else {
            count("SELECT count(*) FROM pending_email_signups WHERE expires_at <> 'infinity' AND email_hash = ?", Sha256.hash(email))
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
        const val OWNER_IP = "203.0.113.40"
        const val STRANGER_IP = "198.51.100.66"
        const val GUESSES = 20
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

    // Recorded under the same name as the lookup it replaced in the sign-up work, so the call lists the
    // existing tests compare are unchanged; the answer is the real one (social-only accounts included).
    override suspend fun addressHasAccount(emailHash: String): Boolean {
        calls += "findEmailIdentity"
        return inner.addressHasAccount(emailHash)
    }

    override suspend fun insertPendingSignup(
        pending: NewPendingSignup,
        now: Instant,
        maxLivePerAddress: Int,
    ): Boolean {
        calls += "insertPendingSignup"
        return inner.insertPendingSignup(pending, now, maxLivePerAddress)
    }

    override suspend fun findLivePendingSignup(
        emailHash: String,
        signupTokenHash: String,
        now: Instant,
    ): PendingSignupRecord? {
        calls += "findLivePendingSignup"
        return inner.findLivePendingSignup(emailHash, signupTokenHash, now)
    }

    override suspend fun replaceCode(
        pendingId: UUID,
        codeHash: String,
        codeExpiresAt: Instant,
        maxCodes: Int,
        maxAttempts: Int,
    ): Boolean {
        calls += "replaceCode"
        return inner.replaceCode(pendingId, codeHash, codeExpiresAt, maxCodes, maxAttempts)
    }

    override suspend fun consumeAttempt(
        emailHash: String,
        signupTokenHash: String,
        now: Instant,
    ): SignupAttemptRecord {
        calls += "consumeAttempt"
        return inner.consumeAttempt(emailHash, signupTokenHash, now)
    }

    override suspend fun completeSignup(
        pendingId: UUID,
        emailHash: String,
        expectedCodeHash: String,
        maxAttempts: Int,
        userId: UUID,
        identityId: UUID,
        displayAge: Int,
        verifiedAt: Instant,
    ): UserRecord? {
        calls += "completeSignup"
        return inner.completeSignup(pendingId, emailHash, expectedCodeHash, maxAttempts, userId, identityId, displayAge, verifiedAt)
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

    override suspend fun markVerifiedForLocalDev(
        userId: UUID,
        verifiedAt: Instant,
    ) {
        calls += "markVerifiedForLocalDev"
        inner.markVerifiedForLocalDev(userId, verifiedAt)
    }
}
