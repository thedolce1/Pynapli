package com.pynapli.server.auth

import com.pynapli.server.auth.security.PasswordHash
import com.pynapli.server.auth.security.PasswordHasher
import com.pynapli.server.auth.security.Sha256
import com.pynapli.server.auth.security.TokenGenerator
import com.pynapli.server.auth.security.normalizeEmail
import com.pynapli.server.safety.RateLimitService
import com.pynapli.server.safety.RateLimitedAction
import com.pynapli.server.safety.rateLimitNetwork
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
import java.util.concurrent.ThreadLocalRandom

/**
 * Email sign-up that proves the person owns the address and never reveals whether it already has
 * an account (ONBOARDING_AUTH_SPEC, "Email sign-up").
 *
 * 1. [startSignup] validates the request (format, a strict single address, password policy, age
 *    gate, acceptances), hashes the password, and answers the same [EmailSignupStartedDto] for every
 *    valid request. On the request it does the same work for every address: rate limits,
 *    validation, one password hash, one random token, one queued job. It never looks the address up.
 * 2. Off the request path ([launchSignupWork], bounded): a new address gets its own pending sign-up
 *    (one row per token, so nobody else's sign-up can replace it) and a 6-digit code by email; an
 *    address that already has an account (any status but deleted, as sign-in treats them) gets a
 *    neutral "someone tried to sign up with this address" email, and nothing is created or changed.
 * 3. [verify] takes the address, the code and the token the sign-up response returned. It first
 *    spends one attempt in a single statement that always writes exactly one row: the matching live
 *    sign-up, or the permanent placeholder row when there is none (V118). Only then is the code
 *    compared. So concurrent guesses can't outrun the cap, and a failure does the same work whether
 *    or not the address had a pending sign-up. Success creates the account from the pending row in
 *    one transaction, exactly as sign-up always created it, and returns the session.
 *
 * Guess budget: [MAX_ATTEMPTS] per pending sign-up for its whole life (a resend issues a new code but
 * never gives attempts back, and a row gets at most [MAX_CODES] codes). Rows are bounded by the email
 * caps, so per address per day at most [ADDRESS_DAILY_EMAIL_CAP] x [MAX_ATTEMPTS] guesses exist.
 */
class EmailSignupService(
    private val repository: EmailSignupRepository,
    private val mailer: SignupMailer,
    private val authService: AuthService,
    private val passwordHasher: PasswordHasher = PasswordHasher(),
    private val clock: Clock = Clock.systemUTC(),
    private val rateLimitService: RateLimitService? = null,
    /** Runs member-only work off the request path: the bounded background launcher in production. */
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
        if (!EmailAddressRules.isStrict(account)) throw AuthException("INVALID_EMAIL")
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
                account = account,
                clientIp = clientIp,
                emailHash = Sha256.hash(account),
                signupTokenHash = Sha256.hash(signupToken),
                passwordHash = passwordHash,
                birthdate = ageGate.birthdate,
                acceptedAt = ageGate.acceptedAt,
            )
        launchSignupWork { deliverSignup(draft) }
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
        if (!EmailAddressRules.isStrict(account)) throw AuthException("INVALID_EMAIL")
        val emailHash = Sha256.hash(account)
        val tokenHash = Sha256.hash(signupToken.trim())
        launchSignupWork { deliverResend(account, clientIp, emailHash, tokenHash) }
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
        val emailHash = Sha256.hash(account)

        // Spend the attempt first, atomically, on exactly one real row (the sign-up, or the placeholder
        // when there is none). Then compare: N parallel guesses get N distinct attempt numbers, and
        // only the first MAX_ATTEMPTS are compared at all.
        val attempt = repository.consumeAttempt(emailHash, Sha256.hash(signupToken.trim()), now)
        val codeMatches = sameHash(attempt.codeHash, codeHash(attempt.id, code.trim()))
        val usable =
            !isPlaceholder(attempt.id) &&
                attempt.attempts <= MAX_ATTEMPTS &&
                attempt.codeExpiresAt.isAfter(now)
        if (!usable || !codeMatches) throw AuthException("INVALID_SIGNUP_CODE")

        val user =
            repository.completeSignup(
                pendingId = attempt.id,
                emailHash = emailHash,
                expectedCodeHash = attempt.codeHash,
                maxAttempts = MAX_ATTEMPTS,
                userId = UUID.randomUUID(),
                identityId = UUID.randomUUID(),
                displayAge = Period.between(attempt.birthdate, LocalDate.now(clock)).years,
                verifiedAt = now,
            )
        // A concurrent verify won, or the address got an account meanwhile: same answer as any other
        // failure (this branch needs the right code, so it tells a guesser nothing).
        return user?.let { authService.startFirstSession(it, deviceId, platform, appVersion) }
            ?: throw AuthException("INVALID_SIGNUP_CODE")
    }

    /**
     * Local development only (route gated: off by default, local environment, local file mailer,
     * loopback peer without forwarding headers): creates a verified account at once through the same
     * account-creation function as [verify], so the seeds and load tests need no email. Unlike the
     * public flow it answers EMAIL_ALREADY_EXISTS.
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
        rateLimitGuard.enforce(RateLimitedAction.AUTH_DEV_SIGNUP, DEV_SIGNUP_SUBJECT)
        validator.validateEmailPassword(email, password)
        val account = normalizeEmail(email)
        if (!EmailAddressRules.isStrict(account)) throw AuthException("INVALID_EMAIL")
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
                emailHash = Sha256.hash(account),
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

    private suspend fun deliverSignup(draft: SignupDraft) {
        runLogged("sign-up") {
            // Over a silent email cap: a complete no-op.
            if (!withinSendCaps(draft.account, draft.clientIp)) return@runLogged
            if (repository.findEmailIdentity(draft.emailHash) != null) {
                mailer.sendAlreadyRegisteredNotice(draft.account)
                return@runLogged
            }
            val now = clock.instant()
            val pendingId = UUID.randomUUID()
            val code = codeGenerator()
            val inserted =
                repository.insertPendingSignup(
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
                    maxLivePerAddress = MAX_LIVE_PER_ADDRESS,
                )
            // Already MAX_LIVE_PER_ADDRESS sign-ups waiting for this address: refuse, never evict, so
            // nobody can push the owner's sign-up out by starting more.
            if (inserted) mailer.sendSignupCode(draft.account, code)
        }
    }

    private suspend fun deliverResend(
        account: String,
        clientIp: String?,
        emailHash: String,
        tokenHash: String,
    ) {
        runLogged("sign-up resend") {
            val now = clock.instant()
            val pending = repository.findLivePendingSignup(emailHash, tokenHash, now) ?: return@runLogged
            // Checked only once the token matched, so only the device that started the sign-up can
            // spend the address's email budget through resend.
            if (!withinSendCaps(account, clientIp)) return@runLogged
            val code = codeGenerator()
            // A new code, never new attempts; at most MAX_CODES codes per sign-up, and none once its
            // guesses are spent (a code it could never use would only mislead).
            if (repository.replaceCode(pending.id, codeHash(pending.id, code), now.plus(CODE_LIFETIME), MAX_CODES, MAX_ATTEMPTS)) {
                mailer.sendSignupCode(account, code)
            }
        }
    }

    /**
     * Counts one email against the silent caps; false when any is spent. Tight per address AND
     * client IP (one network can't starve the owner's own sign-up from theirs), looser per address
     * alone to cap a flood from many networks. "Address" is the address family (any +tag removed),
     * so +variants share every budget.
     */
    private suspend fun withinSendCaps(
        account: String,
        clientIp: String?,
    ): Boolean {
        val limiter = rateLimitService ?: return true
        // The address family (+tags removed) everywhere, so +variants can't multiply any budget.
        val family = EmailAddressRules.withoutPlusTag(account)
        val perNetwork = addressAndClient(family, clientIp)
        // In order, stopping at the first that is spent: an email refused for one network never
        // counts against the address family, so one network can't use up the owner's family budget.
        if (!limiter.checkSubject(perNetwork, RateLimitedAction.AUTH_SIGNUP_EMAIL_HOURLY, fallbackOnOutage = true).allowed) return false
        if (!limiter.checkSubject(perNetwork, RateLimitedAction.AUTH_SIGNUP_EMAIL_DAILY, fallbackOnOutage = true).allowed) return false
        return limiter.checkSubject(family, RateLimitedAction.AUTH_SIGNUP_EMAIL_ADDRESS_DAILY, fallbackOnOutage = true).allowed
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

    // Salted with the pending sign-up, so the same code on two sign-ups never shares a hash.
    private fun codeHash(
        pendingId: UUID,
        code: String,
    ): String = Sha256.hash("$pendingId:$code")

    private fun addressAndClient(
        account: String,
        clientIp: String?,
    ): String = "$account|${clientIp?.trim()?.takeIf { it.isNotEmpty() }?.let(::rateLimitNetwork) ?: "unknown-address"}"

    private class SignupDraft(
        val account: String,
        val clientIp: String?,
        val emailHash: String,
        val signupTokenHash: String,
        val passwordHash: PasswordHash,
        val birthdate: LocalDate,
        val acceptedAt: Instant,
    )

    companion object {
        private val logger = LoggerFactory.getLogger(EmailSignupService::class.java)
        const val CODE_LENGTH = 6

        /** Same strength and lifetime as the password-reset code. */
        val CODE_LIFETIME: Duration = Duration.ofMinutes(30)

        /** Guesses per pending sign-up for its whole life, across all its codes. */
        const val MAX_ATTEMPTS = 5

        /** Codes one pending sign-up can ever be issued (the first plus two resends). */
        const val MAX_CODES = 3

        /**
         * Live pending sign-ups per address; a further sign-up is refused, nothing is evicted. Equal to
         * the family email cap, so the email caps (per network first) are what binds, not this.
         */
        const val MAX_LIVE_PER_ADDRESS = 20

        /** Sign-up emails per address family per day (RateLimitService AUTH_SIGNUP_EMAIL_ADDRESS_DAILY). */
        const val ADDRESS_DAILY_EMAIL_CAP = 20

        /** How long a started sign-up can be finished (a resend issues a fresh code within it). */
        val PENDING_LIFETIME: Duration = Duration.ofHours(24)

        /**
         * The permanent placeholder rows (V118): ids UUID(0, 0) to UUID(0, 63). A failed verify with no
         * live sign-up spends its attempt on one of them, picked at random, so a flood of such verifies
         * spreads over many row locks instead of queueing on one.
         */
        const val PLACEHOLDER_POOL_SIZE = 64
        val PLACEHOLDER_ID: UUID = UUID(0L, 0L)

        fun randomPlaceholderId(): UUID = UUID(0L, ThreadLocalRandom.current().nextInt(PLACEHOLDER_POOL_SIZE).toLong())

        fun isPlaceholder(id: UUID): Boolean = id.mostSignificantBits == 0L && id.leastSignificantBits in 0L until PLACEHOLDER_POOL_SIZE.toLong()

        private const val DEV_SIGNUP_SUBJECT = "local-dev-signup"
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
     * Under a per-address lock: deletes a batch of expired sign-ups, then inserts [pending] unless the
     * address already has [maxLivePerAddress] live ones (then nothing changes and it answers false).
     * Never replaces or evicts another sign-up.
     */
    suspend fun insertPendingSignup(
        pending: NewPendingSignup,
        now: Instant,
        maxLivePerAddress: Int,
    ): Boolean

    /** The live sign-up started with this token for this address, else null. */
    suspend fun findLivePendingSignup(
        emailHash: String,
        signupTokenHash: String,
        now: Instant,
    ): PendingSignupRecord?

    /**
     * A fresh code for the same sign-up while it has had fewer than [maxCodes] and has spent fewer than
     * [maxAttempts] guesses; attempts untouched. False (nothing changes) otherwise.
     */
    suspend fun replaceCode(
        pendingId: UUID,
        codeHash: String,
        codeExpiresAt: Instant,
        maxCodes: Int,
        maxAttempts: Int,
    ): Boolean

    /**
     * One statement that always updates exactly one row: adds one attempt to the live sign-up for
     * (address, token), or to a randomly picked permanent placeholder row when there is none, and
     * returns that row as it is after the increment.
     */
    suspend fun consumeAttempt(
        emailHash: String,
        signupTokenHash: String,
        now: Instant,
    ): SignupAttemptRecord

    /**
     * In one transaction: removes the sign-up (only while it still holds [expectedCodeHash] and has
     * spent at most [maxAttempts], so of two concurrent verifies exactly one wins), creates the account
     * from it marked email-verified at [verifiedAt], and removes every other sign-up of the address.
     * Null when the row was already taken or the address got an account meanwhile (nothing changes).
     */
    suspend fun completeSignup(
        pendingId: UUID,
        emailHash: String,
        expectedCodeHash: String,
        maxAttempts: Int,
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
    val codesIssued: Int,
)

/** A row right after [EmailSignupRepository.consumeAttempt] spent one attempt on it. */
data class SignupAttemptRecord(
    val id: UUID,
    val attempts: Long,
    val codeHash: String,
    val codeExpiresAt: Instant,
    val birthdate: LocalDate,
)
