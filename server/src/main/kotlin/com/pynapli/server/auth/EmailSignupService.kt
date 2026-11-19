package com.pynapli.server.auth

import com.pynapli.server.auth.security.PasswordHash
import com.pynapli.server.auth.security.PasswordHasher
import com.pynapli.server.auth.security.Sha256
import com.pynapli.server.auth.security.TokenGenerator
import com.pynapli.server.auth.security.normalizeEmail
import com.pynapli.server.safety.RateLimitService
import com.pynapli.server.safety.RateLimitedAction
import com.pynapli.shared.network.dto.AuthSessionDto
import com.pynapli.shared.network.dto.EmailSignupStartedDto
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.Period
import java.util.UUID

/**
 * Email sign-up that proves the person owns the address and never reveals whether it already has
 * an account (ONBOARDING_AUTH_SPEC, "Membership privacy").
 *
 * 1. [startSignup] validates the request exactly as sign-up always has (format, password policy,
 *    age gate, acceptances), hashes the password, and answers the same [EmailSignupStartedDto] for
 *    every syntactically valid request. The only work it does on the request is the same for every
 *    address: rate limits, validation, one password hash, one random token, one queued job. It never
 *    looks the address up.
 * 2. Off the request path ([launchSignupWork]): a new address gets a pending sign-up and a 6-digit
 *    code by email; an address that already has an account (any status but deleted, as sign-in
 *    treats them) gets a neutral "someone tried to sign up with this address" email, and nothing is
 *    created or changed. A deleted account's address is free again, as it was before this change.
 * 3. [verify] takes the address, the code and the token the sign-up response returned. Every
 *    failure is the same INVALID_SIGNUP_CODE after the same work (one lookup, one hash comparison,
 *    one failed-attempt write, against a placeholder row when nothing matches). Success creates the
 *    account from the pending row in one transaction, exactly as sign-up created it before, and
 *    returns the session.
 *
 * The token is what keeps a person who only knows someone's address from finishing that person's
 * sign-up with a password of their own (start a second sign-up, wait for the owner to type the new
 * code). It also keeps them from burning the owner's code attempts: wrong guesses only count when
 * the token matches.
 */
class EmailSignupService(
    private val repository: EmailSignupRepository,
    private val mailer: SignupMailer,
    private val authService: AuthService,
    private val passwordHasher: PasswordHasher = PasswordHasher(),
    private val clock: Clock = Clock.systemUTC(),
    private val rateLimitService: RateLimitService? = null,
    /** Runs member-only work off the request path: the application background scope in production. */
    private val launchSignupWork: (suspend () -> Unit) -> Unit,
    private val codeGenerator: () -> String = ::newSignupCode,
    private val tokenGenerator: () -> String = TokenGenerator::newToken,
) {
    private val validator = AuthRequestValidator(clock)
    private val rateLimitGuard = AuthRateLimitGuard(rateLimitService)

    suspend fun startSignup(
        email: String,
        password: String,
        birthdate: String?,
        confirmedAdult: Boolean,
        acceptedTerms: Boolean,
        acceptedPrivacy: Boolean,
        deviceId: String?,
        clientIp: String?,
    ): EmailSignupStartedDto {
        // Fails closed for everyone alike: without a mailer nobody could ever finish.
        if (!mailer.isConfigured) throw AuthException("SIGNUP_UNAVAILABLE")
        val account = normalizeEmail(email)
        // The visible limit is per address AND client address, so someone elsewhere can't use it to
        // stop the owner signing up; the per-device one stops one install spraying addresses.
        rateLimitGuard.enforce(RateLimitedAction.AUTH_SIGNUP, addressAndClient(account, clientIp), deviceId)
        validator.validateEmailPassword(email, password)
        val ageGate =
            validator.validateSignupAgeGate(
                birthdate = birthdate,
                confirmedAdult = confirmedAdult,
                acceptedTerms = acceptedTerms,
                acceptedPrivacy = acceptedPrivacy,
            )
        // Hashed here for every address (same cost either way) so the plaintext never outlives the
        // request; only the hash travels to the queued work.
        val passwordHash = passwordHasher.hash(password)
        val signupToken = tokenGenerator()
        val draft =
            SignupDraft(
                address = email.trim(),
                emailHash = Sha256.hash(account),
                signupTokenHash = Sha256.hash(signupToken),
                passwordHash = passwordHash,
                birthdate = ageGate.birthdate,
                acceptedAt = ageGate.acceptedAt,
            )
        launchSignupWork { deliverSignup(account, draft) }
        return EmailSignupStartedDto(
            verificationRequired = true,
            signupToken = signupToken,
            codeLength = CODE_LENGTH,
            codeExpiresInSeconds = CODE_LIFETIME.seconds,
        )
    }

    /** Same answer whether or not anything is sent; only the device holding the token gets a code. */
    suspend fun resendCode(
        email: String,
        signupToken: String,
        deviceId: String?,
        clientIp: String?,
    ) {
        if (!mailer.isConfigured) throw AuthException("SIGNUP_UNAVAILABLE")
        val account = normalizeEmail(email)
        rateLimitGuard.enforce(RateLimitedAction.AUTH_SIGNUP_RESEND, addressAndClient(account, clientIp), deviceId)
        if (!account.contains("@")) throw AuthException("INVALID_EMAIL")
        val emailHash = Sha256.hash(account)
        val tokenHash = Sha256.hash(signupToken.trim())
        val address = email.trim()
        launchSignupWork { deliverResend(account, address, emailHash, tokenHash) }
    }

    suspend fun verify(
        email: String,
        code: String,
        signupToken: String,
        deviceId: String?,
        platform: String?,
        appVersion: String?,
        clientIp: String?,
    ): AuthSessionDto {
        val account = normalizeEmail(email)
        rateLimitGuard.enforce(RateLimitedAction.AUTH_SIGNUP_VERIFY, addressAndClient(account, clientIp))
        val now = clock.instant()
        val pending = repository.findLivePendingSignup(Sha256.hash(account), now)

        // Both comparisons always run, against placeholders when there is no row, so a missing
        // sign-up, a wrong token and a wrong code cost the same.
        val tokenMatches = sameHash(pending?.signupTokenHash ?: PLACEHOLDER_HASH, Sha256.hash(signupToken.trim())) && pending != null
        val codeMatches = sameHash(pending?.codeHash ?: PLACEHOLDER_HASH, codeHash(pending?.id ?: PLACEHOLDER_ID, code.trim()))
        val usable =
            pending != null &&
                tokenMatches &&
                pending.failedAttempts < MAX_FAILED_ATTEMPTS &&
                pending.codeExpiresAt.isAfter(now)
        if (pending == null || !usable || !codeMatches) {
            // Always one write. Only a guess made with the right token counts against the code, so
            // someone who just knows the address can't use up the owner's attempts.
            repository.recordFailedAttempt(if (pending != null && tokenMatches) pending.id else PLACEHOLDER_ID)
            throw AuthException("INVALID_SIGNUP_CODE")
        }

        val user =
            repository.completeSignup(
                pendingId = pending.id,
                expectedCodeHash = pending.codeHash,
                userId = UUID.randomUUID(),
                identityId = UUID.randomUUID(),
                displayAge = Period.between(pending.birthdate, LocalDate.now(clock)).years,
                verifiedAt = now,
            )
        if (user == null) {
            // A concurrent verify won, or the address got an account meanwhile: same answer as any
            // other failure.
            repository.recordFailedAttempt(PLACEHOLDER_ID)
            throw AuthException("INVALID_SIGNUP_CODE")
        }
        return authService.startFirstSession(user, deviceId, platform, appVersion)
    }

    /**
     * Local development only (route gated by `devSignupShortcutsEnabled` and PYNAPLI_ENV=local):
     * creates a verified account at once through the same account-creation function as [verify], so
     * the seeds and load tests need no email. Unlike the public flow it answers EMAIL_ALREADY_EXISTS.
     */
    suspend fun createVerifiedAccountForLocalDev(
        email: String,
        password: String,
        birthdate: String?,
        confirmedAdult: Boolean,
        acceptedTerms: Boolean,
        acceptedPrivacy: Boolean,
        deviceId: String?,
        platform: String?,
        appVersion: String?,
    ): AuthSessionDto {
        validator.validateEmailPassword(email, password)
        val ageGate =
            validator.validateSignupAgeGate(
                birthdate = birthdate,
                confirmedAdult = confirmedAdult,
                acceptedTerms = acceptedTerms,
                acceptedPrivacy = acceptedPrivacy,
            )
        val user =
            repository.createVerifiedAccount(
                userId = UUID.randomUUID(),
                identityId = UUID.randomUUID(),
                emailHash = Sha256.hash(normalizeEmail(email)),
                passwordHash = passwordHasher.hash(password),
                birthdate = ageGate.birthdate,
                displayAge = ageGate.displayAge,
                acceptedAt = ageGate.acceptedAt,
                termsVersion = AUTH_TERMS_VERSION,
                privacyVersion = AUTH_PRIVACY_VERSION,
                verifiedAt = clock.instant(),
            ) ?: throw AuthException("EMAIL_ALREADY_EXISTS")
        return authService.startFirstSession(user, deviceId, platform, appVersion)
    }

    private suspend fun deliverSignup(
        account: String,
        draft: SignupDraft,
    ) {
        runLogged("sign-up") {
            // Over the silent per-address cap: a complete no-op, so a flood can't keep replacing the
            // owner's pending sign-up either.
            if (!withinSendCaps(account)) return@runLogged
            if (repository.findEmailIdentity(draft.emailHash) != null) {
                mailer.sendAlreadyRegisteredNotice(draft.address)
                return@runLogged
            }
            val now = clock.instant()
            val pendingId = UUID.randomUUID()
            val code = codeGenerator()
            repository.replacePendingSignup(
                NewPendingSignup(
                    id = pendingId,
                    emailHash = draft.emailHash,
                    signupTokenHash = draft.signupTokenHash,
                    passwordHash = draft.passwordHash,
                    birthdate = draft.birthdate,
                    acceptedAt = draft.acceptedAt,
                    termsVersion = AUTH_TERMS_VERSION,
                    privacyVersion = AUTH_PRIVACY_VERSION,
                    codeHash = codeHash(pendingId, code),
                    codeExpiresAt = now.plus(CODE_LIFETIME),
                    expiresAt = now.plus(PENDING_LIFETIME),
                    createdAt = now,
                ),
                now = now,
            )
            mailer.sendSignupCode(draft.address, code)
        }
    }

    private suspend fun deliverResend(
        account: String,
        address: String,
        emailHash: String,
        tokenHash: String,
    ) {
        runLogged("sign-up resend") {
            val now = clock.instant()
            val pending = repository.findLivePendingSignup(emailHash, now) ?: return@runLogged
            if (!sameHash(pending.signupTokenHash, tokenHash)) return@runLogged
            // Checked only once the token matched, so only the device that started the sign-up can
            // spend the address's email budget through resend.
            if (!withinSendCaps(account)) return@runLogged
            val code = codeGenerator()
            if (repository.replaceCode(pending.id, codeHash(pending.id, code), now.plus(CODE_LIFETIME))) {
                mailer.sendSignupCode(address, code)
            }
        }
    }

    /** Counts one email against both silent per-address caps; false when either is spent. */
    private suspend fun withinSendCaps(account: String): Boolean {
        val limiter = rateLimitService ?: return true
        val hourly = limiter.checkSubject(account, RateLimitedAction.AUTH_SIGNUP_EMAIL_HOURLY, fallbackOnOutage = true)
        val daily = limiter.checkSubject(account, RateLimitedAction.AUTH_SIGNUP_EMAIL_DAILY, fallbackOnOutage = true)
        return hourly.allowed && daily.allowed
    }

    private suspend fun runLogged(
        what: String,
        block: suspend () -> Unit,
    ) {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // Exception class only: never the address, the code or anything that says which branch ran.
            logger.error("Background {} work failed ({})", what, error.javaClass.simpleName)
        }
    }

    // Salted with the pending sign-up, so the same code on two sign-ups never shares a hash and a
    // replaced sign-up's code stops working.
    private fun codeHash(
        pendingId: UUID,
        code: String,
    ): String = Sha256.hash("$pendingId:$code")

    private fun addressAndClient(
        account: String,
        clientIp: String?,
    ): String = "$account|${clientIp?.trim()?.takeIf { it.isNotEmpty() } ?: "unknown-address"}"

    private class SignupDraft(
        val address: String,
        val emailHash: String,
        val signupTokenHash: String,
        val passwordHash: PasswordHash,
        val birthdate: LocalDate,
        val acceptedAt: Instant,
    )

    companion object {
        private val logger = LoggerFactory.getLogger(EmailSignupService::class.java)
        const val CODE_LENGTH = 6

        /** Same strength, lifetime and attempt limit as the password-reset code. */
        val CODE_LIFETIME: Duration = Duration.ofMinutes(30)
        const val MAX_FAILED_ATTEMPTS = 5

        /** How long a started sign-up can be finished (a resend issues a fresh code within it). */
        val PENDING_LIFETIME: Duration = Duration.ofHours(24)

        private val PLACEHOLDER_ID: UUID = UUID(0L, 0L)
        private val PLACEHOLDER_HASH: String = Sha256.hash("pynapli-signup-placeholder")
        private val random = SecureRandom()

        fun newSignupCode(): String = random.nextInt(1_000_000).toString().padStart(CODE_LENGTH, '0')

        private fun sameHash(
            expected: String,
            presented: String,
        ): Boolean = MessageDigest.isEqual(expected.toByteArray(Charsets.UTF_8), presented.toByteArray(Charsets.UTF_8))
    }
}

interface EmailSignupRepository {
    /** The address's account, if any (deleted accounts excluded, as sign-in excludes them). */
    suspend fun findEmailIdentity(emailHash: String): EmailIdentityRecord?

    /**
     * Inserts the address's pending sign-up, or atomically replaces the one already there (new id,
     * token, password, code and lifetime), so concurrent sign-ups for one address leave one row.
     * Also deletes a batch of expired pending sign-ups.
     */
    suspend fun replacePendingSignup(
        pending: NewPendingSignup,
        now: Instant,
    )

    /** The address's pending sign-up while it is still within its 24 hours, else null. */
    suspend fun findLivePendingSignup(
        emailHash: String,
        now: Instant,
    ): PendingSignupRecord?

    /** A fresh code for the same pending sign-up (attempts back to zero). False when the row is gone. */
    suspend fun replaceCode(
        pendingId: UUID,
        codeHash: String,
        codeExpiresAt: Instant,
    ): Boolean

    /** One write whether or not [pendingId] exists. */
    suspend fun recordFailedAttempt(pendingId: UUID)

    /**
     * In one transaction: removes the pending sign-up (only while it still holds [expectedCodeHash],
     * so of two concurrent verifies exactly one wins) and creates the account from it, marked
     * email-verified at [verifiedAt]. Null when the row was already taken or the address got an
     * account meanwhile (nothing changes then).
     */
    suspend fun completeSignup(
        pendingId: UUID,
        expectedCodeHash: String,
        userId: UUID,
        identityId: UUID,
        displayAge: Int,
        verifiedAt: Instant,
    ): UserRecord?

    /** Local dev shortcut: the same account rows at once. Null when the address already has an account. */
    suspend fun createVerifiedAccount(
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
    ): UserRecord?
}

class NewPendingSignup(
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
    val expiresAt: Instant,
    val createdAt: Instant,
)

data class PendingSignupRecord(
    val id: UUID,
    val signupTokenHash: String,
    val codeHash: String,
    val codeExpiresAt: Instant,
    val failedAttempts: Int,
    val birthdate: LocalDate,
)
