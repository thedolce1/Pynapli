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
import com.pynapli.server.auth.SignupAttemptRecord
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
    val codes: MutableList<Pair<String, String>> = java.util.Collections.synchronizedList(mutableListOf())
    val notices: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())

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
 * every test that just needs an account. Defaults to the shared test database; the in-memory
 * AuthService unit tests pass an [InMemoryEmailSignupRepository].
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
 * In-memory pending sign-ups, only for the in-memory AuthService unit tests; everything where the
 * SQL is the point is tested against the database (EmailSignupIntegrationTest). Accounts are created
 * through [accounts] (an in-memory [AuthRepository], which throws EMAIL_ALREADY_EXISTS for a taken
 * address). Follows JdbcEmailSignupRepository's rules, placeholder included.
 */
internal class InMemoryEmailSignupRepository(
    private val accounts: AuthRepository,
) : EmailSignupRepository {
    private val rows = linkedMapOf<UUID, StoredPending>()
    private var placeholderAttempts = 0L

    override suspend fun findEmailIdentity(emailHash: String): EmailIdentityRecord? = accounts.findEmailIdentity(emailHash)

    override suspend fun insertPendingSignup(
        pending: NewPendingSignup,
        now: Instant,
        maxLivePerAddress: Int,
    ): Boolean {
        rows.values.removeIf { !it.expiresAt.isAfter(now) }
        if (rows.values.count { it.emailHash == pending.emailHash } >= maxLivePerAddress) return false
        rows[pending.id] =
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
                codesIssued = 1,
                attempts = 0,
                expiresAt = pending.expiresAt,
            )
        return true
    }

    override suspend fun findLivePendingSignup(
        emailHash: String,
        signupTokenHash: String,
        now: Instant,
    ): PendingSignupRecord? = live(emailHash, signupTokenHash, now)?.let { PendingSignupRecord(it.id, it.codesIssued) }

    override suspend fun replaceCode(
        pendingId: UUID,
        codeHash: String,
        codeExpiresAt: Instant,
        maxCodes: Int,
        maxAttempts: Int,
    ): Boolean {
        val row = rows[pendingId]?.takeIf { it.codesIssued < maxCodes && it.attempts < maxAttempts } ?: return false
        rows[pendingId] = row.copy(codeHash = codeHash, codeExpiresAt = codeExpiresAt, codesIssued = row.codesIssued + 1)
        return true
    }

    override suspend fun consumeAttempt(
        emailHash: String,
        signupTokenHash: String,
        now: Instant,
    ): SignupAttemptRecord {
        val row = live(emailHash, signupTokenHash, now)
        if (row == null) {
            placeholderAttempts++
            return SignupAttemptRecord(EmailSignupService.PLACEHOLDER_ID, placeholderAttempts, "placeholder", Instant.EPOCH, LocalDate.of(1900, 1, 1))
        }
        val spent = row.copy(attempts = row.attempts + 1)
        rows[row.id] = spent
        return SignupAttemptRecord(spent.id, spent.attempts, spent.codeHash, spent.codeExpiresAt, spent.birthdate)
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
        val pending = rows[pendingId]?.takeIf { it.codeHash == expectedCodeHash && it.attempts <= maxAttempts } ?: return null
        val user =
            create(userId, identityId, pending.emailHash, pending.passwordHash, pending.birthdate, displayAge, pending.acceptedAt, pending.termsVersion, pending.privacyVersion)
                ?: return null
        rows.values.removeIf { it.emailHash == emailHash }
        return user
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

    private fun live(
        emailHash: String,
        signupTokenHash: String,
        now: Instant,
    ): StoredPending? = rows.values.firstOrNull { it.emailHash == emailHash && it.signupTokenHash == signupTokenHash && it.expiresAt.isAfter(now) }

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
        val codesIssued: Int,
        val attempts: Long,
        val expiresAt: Instant,
    )
}
