package com.pynapli.server.auth.social

import com.pynapli.server.auth.AUTH_PRIVACY_VERSION
import com.pynapli.server.auth.AUTH_TERMS_VERSION
import com.pynapli.server.auth.AuthException
import com.pynapli.server.auth.AuthRequestValidator
import com.pynapli.server.auth.AuthService
import com.pynapli.server.auth.NewSocialIdentity
import com.pynapli.server.auth.UserRecord
import com.pynapli.server.auth.security.Sha256
import com.pynapli.server.auth.security.TokenGenerator
import com.pynapli.server.safety.RateLimitService
import com.pynapli.server.safety.RateLimitedAction
import com.pynapli.shared.network.dto.AuthSessionDto
import com.pynapli.shared.network.dto.LinkSocialIdentityRequestDto
import com.pynapli.shared.network.dto.LinkedIdentitiesDto
import com.pynapli.shared.network.dto.LinkedIdentityDto
import com.pynapli.shared.network.dto.SocialNonceDto
import com.pynapli.shared.network.dto.SocialSignInConfigDto
import com.pynapli.shared.network.dto.SocialSignInRequestDto
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.util.UUID

/**
 * Continue with Google / Apple (ONBOARDING_AUTH_SPEC "Social sign-in"; API_CONTRACT_SPEC "Section 5
 * contract"). The provider proves who the person is; the server still owns the account, the age gate,
 * the session and every rule below.
 *
 * Every endpoint that takes an ID token first verifies it ([SocialIdentityVerifier]) and then spends
 * the server nonce it carries, atomically, before anything else: a token is usable for exactly one
 * answer, whatever that answer is (including `SOCIAL_SIGNUP_NEEDS_AGE_GATE`).
 *
 * Accounts are keyed by the provider's `sub`, never by email, and are never linked automatically by
 * email (a takeover risk, and it would tell the caller who is a member). An unknown identity whose
 * provider-vouched address already belongs to an account gets `SOCIAL_EMAIL_HAS_ACCOUNT`: only the
 * verified owner of that address can get that answer. Linking is a signed-in action from Settings.
 */
class SocialAuthService(
    private val verifiers: Map<SocialProvider, SocialIdentityVerifier>,
    private val nonceStore: SocialNonceStore,
    private val repository: SocialIdentityRepository,
    private val authService: AuthService,
    private val tokenSeal: SocialTokenSeal,
    private val appleTokenClient: AppleTokenClient? = null,
    private val appleGrantRevoker: AppleGrantRevoker? = null,
    private val rateLimitService: RateLimitService? = null,
    private val clock: Clock = Clock.systemUTC(),
    /** Off-request work (the Apple code exchange); production passes a bounded background launcher. */
    private val launchBackground: (suspend () -> Unit) -> Unit = { },
    /** "Hide from people you know": a new account's proven address is bound before its first session. */
    private val onAccountVerified: (suspend (userId: UUID, normalisedEmail: String) -> Unit)? = null,
    /** The same settling as email sign-in, when the token's proven address is the account's address. */
    private val onSignInWithAccountAddress: (suspend (userId: UUID, normalisedEmail: String) -> Unit)? = null,
    private val tokenGenerator: () -> String = TokenGenerator::newToken,
) {
    private val validator = AuthRequestValidator(clock)

    fun config(): SocialSignInConfigDto =
        SocialSignInConfigDto(
            google = SocialProvider.GOOGLE in verifiers,
            apple = SocialProvider.APPLE in verifiers,
        )

    /** Rate-limited per client IP by the request plugin (AUTH_SOCIAL_NONCE_IP). */
    suspend fun issueNonce(): SocialNonceDto {
        if (verifiers.isEmpty()) throw AuthException("SOCIAL_SIGN_IN_UNAVAILABLE")
        val nonce = tokenGenerator()
        storeCall { nonceStore.remember(nonce, NONCE_LIFETIME) }
        return SocialNonceDto(nonce = nonce, expiresInSeconds = NONCE_LIFETIME.seconds.toInt())
    }

    suspend fun signIn(
        providerWire: String?,
        request: SocialSignInRequestDto,
    ): AuthSessionDto {
        val identity = verifyAndSpend(providerWire, request.idToken, request.nonce)
        enforce(RateLimitedAction.AUTH_SOCIAL_SIGN_IN, subjectKey(identity))

        repository.findIdentity(identity.provider, identity.subject)?.let { existing ->
            return signInExisting(existing, identity, request)
        }

        val emailHash = identity.authoritativeEmail?.let(Sha256::hash)
        // Before the age gate, so nobody types a birthday only to be told they already have an account.
        if (emailHash != null && repository.emailHasAccount(emailHash)) throw AuthException("SOCIAL_EMAIL_HAS_ACCOUNT")
        if (request.birthdate.isNullOrBlank()) throw AuthException("SOCIAL_SIGNUP_NEEDS_AGE_GATE")
        val ageGate =
            validator.validateSignupAgeGate(
                birthdate = request.birthdate,
                confirmedAdult = request.confirmedAdult,
                acceptedTerms = request.acceptedTerms,
                acceptedPrivacy = request.acceptedPrivacy,
            )
        val identityId = UUID.randomUUID()
        val created =
            repository.createAccount(
                userId = UUID.randomUUID(),
                identity = identity.toNewIdentity(identityId),
                emailHash = emailHash,
                birthdate = ageGate.birthdate,
                displayAge = ageGate.displayAge,
                acceptedAt = ageGate.acceptedAt,
                termsVersion = AUTH_TERMS_VERSION,
                privacyVersion = AUTH_PRIVACY_VERSION,
                emailVerifiedAt = if (emailHash != null) clock.instant() else null,
            )
        return when (created) {
            is SocialAccountCreation.Created -> {
                // Bound before the session exists, exactly as email sign-up's verify does.
                identity.authoritativeEmail?.let { email -> onAccountVerified?.invoke(created.user.id, email) }
                storeAppleTokenLater(identityId, identity, request.authorizationCode)
                authService.startFirstSession(created.user, request.deviceId, request.platform, request.appVersion)
            }
            SocialAccountCreation.EmailTaken -> throw AuthException("SOCIAL_EMAIL_HAS_ACCOUNT")
            // The same person's concurrent sign-up won: sign in to that account.
            SocialAccountCreation.IdentityTaken ->
                repository
                    .findIdentity(identity.provider, identity.subject)
                    ?.let { existing -> signInExisting(existing, identity, request) }
                    ?: throw AuthException("INVALID_SOCIAL_TOKEN")
        }
    }

    private suspend fun signInExisting(
        existing: SocialIdentityRecord,
        identity: VerifiedSocialIdentity,
        request: SocialSignInRequestDto,
    ): AuthSessionDto {
        // As email sign-in: the identity is proven first, then the status decides.
        if (existing.userStatus != "active") throw AuthException("ACCOUNT_NOT_ACTIVE")
        repository.recordSignIn(existing.identityId, identity.emailVerified, identity.emailHint)
        val email = identity.authoritativeEmail
        if (email != null && Sha256.hash(email) == existing.accountEmailHash) onSignInWithAccountAddress?.invoke(existing.userId, email)
        if (!existing.hasAppleRefreshToken) storeAppleTokenLater(existing.identityId, identity, request.authorizationCode)
        return authService.startSocialSignInSession(
            UserRecord(existing.userId, existing.userStatus),
            request.deviceId,
            request.platform,
            request.appVersion,
        )
    }

    /**
     * The account behind a provider identity, for the suspended-account appeal (which can't have a
     * session). The caller proved the identity, so saying it has no account reveals nothing new.
     */
    suspend fun identityForAppeal(
        providerWire: String?,
        idToken: String,
        nonce: String,
    ): SocialIdentityRecord {
        val identity = verifyAndSpend(providerWire, idToken, nonce)
        return repository.findIdentity(identity.provider, identity.subject) ?: throw AuthException("SOCIAL_ACCOUNT_NOT_FOUND")
    }

    suspend fun identities(userId: UUID): LinkedIdentitiesDto {
        val rows = repository.listIdentities(userId)
        return LinkedIdentitiesDto(
            identities = rows.map { LinkedIdentityDto(provider = it.provider, linkedAt = it.linkedAt.toString(), emailHint = it.emailHint) },
            hasPassword = rows.any { it.hasPassword },
        )
    }

    /** Rate-limited per account by the request plugin (SOCIAL_IDENTITY_CHANGE). */
    suspend fun link(
        userId: UUID,
        providerWire: String?,
        request: LinkSocialIdentityRequestDto,
    ): LinkedIdentitiesDto {
        // 400, not 401, on this signed-in route: a 401 makes clients refresh the session and replay the
        // request, which would only resend a spent nonce (API_CONTRACT_SPEC "Section 5 contract").
        val identity =
            try {
                verifyAndSpend(providerWire, request.idToken, request.nonce)
            } catch (error: AuthException) {
                if (error.code != "INVALID_SOCIAL_TOKEN") throw error
                throw AuthException("INVALID_SOCIAL_TOKEN", status = HttpStatusCode.BadRequest)
            }
        val identityId = UUID.randomUUID()
        when (repository.link(userId, identity.toNewIdentity(identityId))) {
            SocialLinkOutcome.Linked -> storeAppleTokenLater(identityId, identity, request.authorizationCode)
            SocialLinkOutcome.AlreadyLinked -> Unit
            // Necessarily says the identity is used; the caller just proved it is theirs.
            SocialLinkOutcome.InUse -> throw AuthException("SOCIAL_IDENTITY_IN_USE")
            SocialLinkOutcome.ProviderAlreadyLinked -> throw AuthException("SOCIAL_PROVIDER_ALREADY_LINKED")
        }
        return identities(userId)
    }

    /** Works even while the provider is switched off, so nobody is stuck with a method they want gone. */
    suspend fun unlink(
        userId: UUID,
        providerWire: String?,
    ): Boolean {
        val provider = SocialProvider.fromWire(providerWire) ?: throw AuthException("SOCIAL_IDENTITY_NOT_LINKED")
        return when (val outcome = repository.unlink(userId, provider)) {
            is SocialUnlinkOutcome.Unlinked -> {
                outcome.appleToken?.let { token -> appleGrantRevoker?.revokeLater(listOf(token)) }
                true
            }
            SocialUnlinkOutcome.NotLinked -> throw AuthException("SOCIAL_IDENTITY_NOT_LINKED")
            SocialUnlinkOutcome.LastSignInMethod -> throw AuthException("LAST_SIGN_IN_METHOD")
        }
    }

    private suspend fun verifyAndSpend(
        providerWire: String?,
        idToken: String,
        nonce: String,
    ): VerifiedSocialIdentity {
        val provider = SocialProvider.fromWire(providerWire) ?: throw AuthException("SOCIAL_SIGN_IN_UNAVAILABLE")
        val verifier = verifiers[provider] ?: throw AuthException("SOCIAL_SIGN_IN_UNAVAILABLE")
        val identity = verifier.verify(idToken, nonce)
        // Only after the signature and claims passed, so a forged token can't burn someone's nonce.
        if (!storeCall { nonceStore.consume(nonce.trim()) }) throw AuthException("INVALID_SOCIAL_TOKEN")
        return identity
    }

    private fun storeAppleTokenLater(
        identityId: UUID,
        identity: VerifiedSocialIdentity,
        authorizationCode: String?,
    ) {
        if (identity.provider != SocialProvider.APPLE) return
        val client = appleTokenClient ?: return
        val code = authorizationCode?.trim()?.takeIf { it.isNotEmpty() } ?: return
        launchBackground {
            try {
                val refreshToken = client.exchangeCode(identity.audience, code, identity.subject) ?: return@launchBackground
                repository.storeAppleRefreshToken(identityId, identity.audience, tokenSeal.seal(identityId, refreshToken))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                logger.warn("Storing the Apple refresh token failed ({})", error.javaClass.simpleName)
            }
        }
    }

    private suspend fun enforce(
        action: String,
        subject: String,
    ) {
        val limiter = rateLimitService ?: return
        if (!limiter.checkSubject(subject, action, fallbackOnOutage = true).allowed) throw AuthException("RATE_LIMITED")
    }

    // The nonce store is Redis: if it is down, social sign-in fails closed with a retryable answer.
    private suspend fun <T> storeCall(block: suspend () -> T): T =
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            logger.warn("Social sign-in nonce store unavailable ({})", error.javaClass.simpleName)
            throw AuthException("SOCIAL_SIGN_IN_TEMPORARILY_UNAVAILABLE")
        }

    private fun subjectKey(identity: VerifiedSocialIdentity): String = "${identity.provider.wire}:${identity.subject}"

    private fun VerifiedSocialIdentity.toNewIdentity(identityId: UUID): NewSocialIdentity =
        NewSocialIdentity(
            identityId = identityId,
            provider = provider.wire,
            subject = subject,
            emailVerified = emailVerified,
            emailHint = emailHint,
        )

    companion object {
        private val logger = LoggerFactory.getLogger(SocialAuthService::class.java)
        val NONCE_LIFETIME: Duration = Duration.ofMinutes(10)
    }
}
