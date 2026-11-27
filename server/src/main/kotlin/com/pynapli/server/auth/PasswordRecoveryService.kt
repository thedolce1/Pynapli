package com.pynapli.server.auth

import com.pynapli.server.auth.security.PasswordHasher
import com.pynapli.server.auth.security.Sha256
import com.pynapli.server.auth.security.normalizeEmail
import com.pynapli.server.safety.RateLimitService
import com.pynapli.server.safety.RateLimitedAction
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Forgot-password and change-password.
 *
 * Forgot password emails a 6-digit code to the address the person types. Pynapli stores only a
 * hash of each email, so the code goes to the typed address once it matches an account; the
 * address itself is never stored. The request can't be used to discover who is on Pynapli: it
 * answers the same way whether or not an account exists, and it never waits on the lookup. The
 * account lookup, the code write and the email all run off the request path, through
 * [launchResetWork] (the server's background scope in production), so a member's request takes no
 * longer than anyone else's and a failed send can't change the answer.
 *
 * Checking a code does the same reads and the same failed-attempt write whether or not the address
 * is a member or has a live code.
 *
 * Every successful reset or change signs out the account's other sessions: someone resetting a
 * password usually suspects another person has it.
 */
class PasswordRecoveryService(
    private val repository: PasswordRecoveryRepository,
    private val mailer: PasswordResetMailer,
    // Runs a reset request's account work off the request path. No default on purpose: running it
    // inline would make a member's request measurably slower than a stranger's again.
    private val launchResetWork: (suspend () -> Unit) -> Unit,
    private val passwordHasher: PasswordHasher = PasswordHasher(),
    private val clock: Clock = Clock.systemUTC(),
    rateLimitService: RateLimitService? = null,
    private val onSessionsRevoked: (UUID) -> Unit = {},
    private val codeGenerator: () -> String = ::newResetCode,
) {
    private val validator = AuthRequestValidator(clock)
    private val rateLimitGuard = AuthRateLimitGuard(rateLimitService)

    suspend fun requestReset(
        email: String,
        deviceId: String?,
    ) {
        if (!mailer.isConfigured) throw AuthException("PASSWORD_RESET_UNAVAILABLE")
        rateLimitGuard.enforce(RateLimitedAction.AUTH_PASSWORD_RESET, email, deviceId)
        val normalized = normalizeEmail(email)
        if (!normalized.contains("@")) throw AuthException("INVALID_EMAIL")

        val typedAddress = email.trim()
        launchResetWork { sendCodeIfMember(normalized, typedAddress) }
    }

    /**
     * After the response: emails a code if [normalized] is an active account, otherwise nothing.
     * Never throws; a failure is logged by exception type only (never the address or the code).
     */
    private suspend fun sendCodeIfMember(
        normalized: String,
        typedAddress: String,
    ) {
        try {
            val identity = repository.findEmailIdentity(Sha256.hash(normalized)) ?: return
            if (identity.userStatus != "active") return

            val code = codeGenerator()
            repository.replaceResetCode(
                codeId = UUID.randomUUID(),
                userId = identity.userId,
                codeHash = codeHash(identity.userId, code),
                expiresAt = clock.instant().plus(CODE_LIFETIME),
            )
            mailer.sendResetCode(email = typedAddress, code = code)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            logger.error("Account recovery request could not be completed ({})", error.javaClass.simpleName)
        }
    }

    suspend fun resetPassword(
        email: String,
        code: String,
        newPassword: String,
    ) {
        rateLimitGuard.enforce(RateLimitedAction.AUTH_PASSWORD_RESET_VERIFY, email)
        validator.validateNewPassword(email, newPassword)
        val identity =
            repository
                .findEmailIdentity(Sha256.hash(normalizeEmail(email)))
                ?.takeIf { it.userStatus == "active" }
        // No account, no live code and a wrong code all do the same reads and the same
        // failed-attempt write (against an id that matches no row when there is no live code), so
        // someone who just requested a reset for an address can't tell from a wrong code whether
        // the address is a member.
        val accountId = identity?.userId ?: UUID.randomUUID()
        val live =
            repository
                .findLiveResetCode(accountId, clock.instant())
                ?.takeIf { identity != null && it.failedAttempts < MAX_FAILED_ATTEMPTS }
        val presented = codeHash(accountId, code.trim()).toByteArray()
        val matches = live != null && MessageDigest.isEqual(live.codeHash.toByteArray(), presented)
        if (identity == null || live == null || !matches) {
            repository.recordFailedAttempt(live?.id ?: UUID.randomUUID())
            throw AuthException("INVALID_RESET_CODE")
        }

        val applied =
            repository.completeReset(
                codeId = live.id,
                userId = identity.userId,
                passwordHash = passwordHasher.hash(newPassword),
            )
        if (!applied) throw AuthException("INVALID_RESET_CODE")
        onSessionsRevoked(identity.userId)
    }

    suspend fun changePassword(
        userId: UUID,
        accessToken: String,
        currentPassword: String,
        newPassword: String,
    ) {
        rateLimitGuard.enforce(RateLimitedAction.AUTH_PASSWORD_CHANGE, userId.toString())
        validator.validateNewPassword(newPassword)
        val stored = repository.findPasswordHash(userId) ?: throw AuthException("PASSWORD_CHANGE_NOT_AVAILABLE")
        if (!passwordHasher.verify(currentPassword, stored)) throw AuthException("WRONG_CURRENT_PASSWORD")
        repository.changePassword(
            userId = userId,
            passwordHash = passwordHasher.hash(newPassword),
            keepAccessTokenHash = Sha256.hash(accessToken),
        )
        onSessionsRevoked(userId)
    }

    // Salted with the account so equal codes on two accounts never share a hash.
    private fun codeHash(
        userId: UUID,
        code: String,
    ): String = Sha256.hash("$userId:$code")

    companion object {
        private val logger = LoggerFactory.getLogger(PasswordRecoveryService::class.java)
        val CODE_LIFETIME: Duration = Duration.ofMinutes(30)
        const val MAX_FAILED_ATTEMPTS = 5
        private val random = SecureRandom()

        fun newResetCode(): String = (random.nextInt(1_000_000)).toString().padStart(6, '0')
    }
}

interface PasswordRecoveryRepository {
    suspend fun findEmailIdentity(emailHash: String): EmailIdentityRecord?

    suspend fun findPasswordHash(userId: UUID): com.pynapli.server.auth.security.PasswordHash?

    /** Retires any earlier unused codes for the user and stores the new one. */
    suspend fun replaceResetCode(
        codeId: UUID,
        userId: UUID,
        codeHash: String,
        expiresAt: Instant,
    )

    suspend fun findLiveResetCode(
        userId: UUID,
        now: Instant,
    ): ResetCodeRecord?

    suspend fun recordFailedAttempt(codeId: UUID)

    /** Sets the password, consumes the code and revokes every session, in one transaction. */
    suspend fun completeReset(
        codeId: UUID,
        userId: UUID,
        passwordHash: com.pynapli.server.auth.security.PasswordHash,
    ): Boolean

    /** Sets the password and revokes every session except the one making the change. */
    suspend fun changePassword(
        userId: UUID,
        passwordHash: com.pynapli.server.auth.security.PasswordHash,
        keepAccessTokenHash: String,
    )
}

data class ResetCodeRecord(
    val id: UUID,
    val codeHash: String,
    val failedAttempts: Int,
)

/** Delivers the reset code. Production needs an email provider (founder setup). */
interface PasswordResetMailer {
    val isConfigured: Boolean

    suspend fun sendResetCode(
        email: String,
        code: String,
    )
}

/**
 * Local development only: appends each code to a git-ignored file (default
 * `.pynapli-local-mail/reset-codes.txt`) so the flow can be exercised without an email provider.
 * Codes never go to the server log. Never wired outside PYNAPLI_ENV=local.
 */
class LocalFilePasswordResetMailer(
    private val file: java.io.File = java.io.File(".pynapli-local-mail/reset-codes.txt"),
) : PasswordResetMailer {
    override val isConfigured: Boolean = true

    override suspend fun sendResetCode(
        email: String,
        code: String,
    ) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            file.parentFile?.mkdirs()
            file.appendText("${Instant.now()} $email $code" + System.lineSeparator())
        }
    }
}

/** No provider configured: the endpoint answers PASSWORD_RESET_UNAVAILABLE so the app can point to support. */
object UnconfiguredPasswordResetMailer : PasswordResetMailer {
    override val isConfigured: Boolean = false

    override suspend fun sendResetCode(
        email: String,
        code: String,
    ) = throw IllegalStateException("No email provider configured")
}
