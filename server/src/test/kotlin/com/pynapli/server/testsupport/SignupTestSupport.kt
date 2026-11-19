package com.pynapli.server.testsupport

import com.pynapli.server.auth.AuthException
import com.pynapli.server.auth.AuthRepository
import com.pynapli.server.auth.AuthService
import com.pynapli.server.auth.EmailIdentityRecord
import com.pynapli.server.auth.EmailSignupRepository
import com.pynapli.server.auth.EmailSignupService
import com.pynapli.server.auth.JdbcEmailSignupRepository
import com.pynapli.server.auth.NewPendingSignup
import com.pynapli.server.auth.PendingSignupRecord
import com.pynapli.server.auth.SignupMailer
import com.pynapli.server.auth.UserRecord
import com.pynapli.server.auth.security.PasswordHash
import com.pynapli.server.auth.security.PasswordHasher
import com.pynapli.server.safety.RateLimitService
import com.pynapli.shared.network.dto.AuthSessionDto
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Holds the sign-up service's off-request work until the test runs it, so a test can see exactly
 * what the request did on its own (nothing member-specific) and then what the queued work did.
 */
internal class QueuedSignupWork {
    private val queue = ArrayDeque<suspend () -> Unit>()

    val size: Int get() = queue.size

    fun launch(work: suspend () -> Unit) {
        queue.addLast(work)
    }

    suspend fun runAll() {
        while (queue.isNotEmpty()) queue.removeFirst().invoke()
    }

    /** Hands the queued jobs to the caller (to run them against each other) and empties the queue. */
    fun drain(): List<suspend () -> Unit> = queue.toList().also { queue.clear() }
}

/** Records every sign-up email instead of sending it. */
internal class CapturingSignupMailer(
    override val isConfigured: Boolean = true,
) : SignupMailer {
    val codes = mutableListOf<Pair<String, String>>()
    val notices = mutableListOf<String>()

    override suspend fun sendSignupCode(
        email: String,
        code: String,
    ) {
        codes += email to code
    }

    override suspend fun sendAlreadyRegisteredNotice(email: String) {
        notices += email
    }

    fun lastCodeFor(email: String): String? = codes.lastOrNull { it.first.equals(email.trim(), ignoreCase = true) }?.second
}

/**
 * Creates an account through the real email sign-up flow (start -> queued work -> emailed code ->
 * verify) and returns the session verify hands back. Replaces the old one-step `signupEmail` in
 * every test that just needs an account. Defaults to the shared test database; unit tests pass an
 * [InMemoryEmailSignupRepository].
 */
internal suspend fun AuthService.signUpVerified(
    email: String,
    password: String,
    birthdate: String? = "1990-01-01",
    deviceId: String? = null,
    platform: String? = null,
    appVersion: String? = null,
    repository: EmailSignupRepository = JdbcEmailSignupRepository(PynapliTestDatabase.dataSource),
    rateLimitService: RateLimitService? = null,
    passwordHasher: PasswordHasher = PasswordHasher(iterations = 1_000),
    confirmedAdult: Boolean = true,
    acceptedTerms: Boolean = true,
    acceptedPrivacy: Boolean = true,
): AuthSessionDto {
    val mailer = CapturingSignupMailer()
    val work = QueuedSignupWork()
    val signup =
        EmailSignupService(
            repository = repository,
            mailer = mailer,
            authService = this,
            passwordHasher = passwordHasher,
            rateLimitService = rateLimitService,
            launchSignupWork = work::launch,
        )
    val started =
        signup.startSignup(
            email = email,
            password = password,
            birthdate = birthdate,
            confirmedAdult = confirmedAdult,
            acceptedTerms = acceptedTerms,
            acceptedPrivacy = acceptedPrivacy,
            deviceId = deviceId,
            clientIp = TEST_CLIENT_IP,
        )
    work.runAll()
    val code = mailer.lastCodeFor(email) ?: error("No sign-up code was emailed (does the address already have an account?)")
    return signup.verify(
        email = email,
        code = code,
        signupToken = started.signupToken,
        deviceId = deviceId,
        platform = platform,
        appVersion = appVersion,
        clientIp = TEST_CLIENT_IP,
    )
}

internal const val TEST_CLIENT_IP = "203.0.113.10"

/**
 * In-memory pending sign-ups for unit tests; accounts are created through [accounts] (an in-memory
 * [AuthRepository]), which throws EMAIL_ALREADY_EXISTS for a taken address.
 */
internal class InMemoryEmailSignupRepository(
    private val accounts: AuthRepository,
) : EmailSignupRepository {
    private val pendingByEmail = linkedMapOf<String, StoredPending>()
    var failedAttemptWrites = 0
        private set

    override suspend fun findEmailIdentity(emailHash: String): EmailIdentityRecord? = accounts.findEmailIdentity(emailHash)

    override suspend fun replacePendingSignup(
        pending: NewPendingSignup,
        now: Instant,
    ) {
        pendingByEmail.entries.removeIf { !it.value.expiresAt.isAfter(now) }
        pendingByEmail[pending.emailHash] =
            StoredPending(
                id = pending.id,
                emailHash = pending.emailHash,
                signupTokenHash = pending.signupTokenHash,
                passwordHash = pending.passwordHash,
                birthdate = pending.birthdate,
                acceptedAt = pending.acceptedAt,
                termsVersion = pending.termsVersion,
                privacyVersion = pending.privacyVersion,
                codeHash = pending.codeHash,
                codeExpiresAt = pending.codeExpiresAt,
                failedAttempts = 0,
                expiresAt = pending.expiresAt,
            )
    }

    override suspend fun findLivePendingSignup(
        emailHash: String,
        now: Instant,
    ): PendingSignupRecord? =
        pendingByEmail[emailHash]
            ?.takeIf { it.expiresAt.isAfter(now) }
            ?.let { PendingSignupRecord(it.id, it.signupTokenHash, it.codeHash, it.codeExpiresAt, it.failedAttempts, it.birthdate) }

    override suspend fun replaceCode(
        pendingId: UUID,
        codeHash: String,
        codeExpiresAt: Instant,
    ): Boolean {
        val entry = pendingByEmail.entries.firstOrNull { it.value.id == pendingId } ?: return false
        entry.setValue(entry.value.copy(codeHash = codeHash, codeExpiresAt = codeExpiresAt, failedAttempts = 0))
        return true
    }

    override suspend fun recordFailedAttempt(pendingId: UUID) {
        failedAttemptWrites++
        val entry = pendingByEmail.entries.firstOrNull { it.value.id == pendingId } ?: return
        entry.setValue(entry.value.copy(failedAttempts = entry.value.failedAttempts + 1))
    }

    override suspend fun completeSignup(
        pendingId: UUID,
        expectedCodeHash: String,
        userId: UUID,
        identityId: UUID,
        displayAge: Int,
        verifiedAt: Instant,
    ): UserRecord? {
        val entry = pendingByEmail.entries.firstOrNull { it.value.id == pendingId && it.value.codeHash == expectedCodeHash } ?: return null
        val pending = entry.value
        pendingByEmail.remove(entry.key)
        return create(userId, identityId, pending.emailHash, pending.passwordHash, pending.birthdate, displayAge, pending.acceptedAt, pending.termsVersion, pending.privacyVersion)
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
    ): UserRecord? = create(userId, identityId, emailHash, passwordHash, birthdate, displayAge, acceptedAt, termsVersion, privacyVersion)

    fun pendingCount(): Int = pendingByEmail.size

    private suspend fun create(
        userId: UUID,
        identityId: UUID,
        emailHash: String,
        passwordHash: PasswordHash,
        birthdate: LocalDate,
        displayAge: Int,
        acceptedAt: Instant,
        termsVersion: String,
        privacyVersion: String,
    ): UserRecord? =
        try {
            accounts.createEmailUser(
                userId = userId,
                identityId = identityId,
                emailHash = emailHash,
                passwordHash = passwordHash,
                birthdate = birthdate,
                displayAge = displayAge,
                adultConfirmedAt = acceptedAt,
                termsAcceptedAt = acceptedAt,
                termsVersion = termsVersion,
                privacyAcceptedAt = acceptedAt,
                privacyVersion = privacyVersion,
            )
        } catch (error: AuthException) {
            if (error.code == "EMAIL_ALREADY_EXISTS") null else throw error
        }

    private data class StoredPending(
        val id: UUID,
        val emailHash: String,
        val signupTokenHash: String,
        val passwordHash: PasswordHash,
        val birthdate: LocalDate,
        val acceptedAt: Instant,
        val termsVersion: String,
        val privacyVersion: String,
        val codeHash: String,
        val codeExpiresAt: Instant,
        val failedAttempts: Int,
        val expiresAt: Instant,
    )
}
