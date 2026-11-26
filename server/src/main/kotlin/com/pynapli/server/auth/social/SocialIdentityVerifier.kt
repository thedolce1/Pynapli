package com.pynapli.server.auth.social

import com.google.api.client.json.webtoken.JsonWebSignature
import com.google.auth.http.HttpTransportFactory
import com.google.auth.oauth2.TokenVerifier
import com.pynapli.server.auth.AuthException
import com.pynapli.server.auth.security.normalizeEmail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

enum class SocialProvider(
    val wire: String,
) {
    GOOGLE("google"),
    APPLE("apple"),
    ;

    companion object {
        fun fromWire(value: String?): SocialProvider? = entries.firstOrNull { it.wire == value?.trim()?.lowercase() }
    }
}

/**
 * What a verified provider ID token says. [subject] is the account key (Google/Apple `sub`, stable and
 * never reused); the email is never a key. [authoritativeEmail] is the normalised address only when the
 * provider vouches for it AND it is the person's real address (see [SocialIdentityVerifier]), else null.
 * Holds the plaintext address only for the request; nothing logs it.
 */
class VerifiedSocialIdentity(
    val provider: SocialProvider,
    val subject: String,
    /** The `aud` that matched: which of our client ids the token was issued to. */
    val audience: String,
    val email: String?,
    val emailVerified: Boolean,
    val privateRelay: Boolean,
    val authoritativeEmail: String?,
) {
    /** Masked address for Settings, or null. */
    val emailHint: String? get() = email?.let(::maskEmail)
}

/**
 * Verifies a Google or Apple ID token: signature against the provider's published keys (JWKS), then
 * every claim the providers' docs require, checked here rather than trusted to the library:
 *
 * - `alg` RS256 (Apple: RS256 or ES256; Apple's docs say ES256, its published keys are RS256);
 * - `iss`: Google `https://accounts.google.com` or `accounts.google.com`; Apple `https://appleid.apple.com`;
 * - `aud`: exactly one value, and one of our accepted client ids;
 * - `exp` present and in the future; `iat` present and not more than [MAX_FUTURE_SKEW] ahead;
 * - `sub` present;
 * - `nonce` equal to the server-issued nonce. Google echoes what it was given. For Apple the client
 *   sends Apple the lowercase hex SHA-256 of the nonce (as Apple's native samples do); the raw value is
 *   accepted too, for web clients that pass it unhashed.
 *
 * Signature, parsing and key fetching are `com.google.auth.oauth2.TokenVerifier` (already on the
 * classpath through firebase-admin, also used for App Check). Its audience/issuer options are left
 * unset on purpose: they compare one string, and both checks need a set. It caches the key set for an
 * hour and never refetches on an unknown `kid`, so on that failure the verifier is rebuilt (a fresh
 * cache) and the token is checked once more, at most once per [keyRefreshMinInterval]. A `kid` that is
 * still unknown after a refresh goes on a small, expiring negative list ([UNKNOWN_KID_TTL], at most
 * [MAX_UNKNOWN_KIDS]) and never triggers another refresh while listed. So bogus kids from an attacker
 * cost at most one key fetch per interval and can delay a real rotation by at most that interval; they
 * can never hold the refresh off. Per verifier, so per server instance.
 *
 * Email rules ([authoritativeEmail]):
 * - Google: `email_verified` true AND (an `@gmail.com` address OR an `hd` claim). Google's docs say only
 *   then is Google authoritative for the address; otherwise its owner may have changed since.
 * - Apple: `email_verified` true (string or boolean) AND not a private relay address
 *   (`is_private_email` true, string or boolean, or an `@privaterelay.appleid.com` address, because the
 *   claim is sometimes missing). A relay address is nobody's known address.
 */
class SocialIdentityVerifier(
    val provider: SocialProvider,
    acceptedAudiences: Collection<String>,
    private val jwksUrl: String = defaultJwksUrl(provider),
    private val clock: Clock = Clock.systemUTC(),
    private val httpTransportFactory: HttpTransportFactory? = null,
    private val keyRefreshMinInterval: Duration = Duration.ofSeconds(30),
) {
    private val audiences: Set<String> = acceptedAudiences.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    private val tokenVerifier = AtomicReference(buildTokenVerifier())

    // Guarded by `this`.
    private var lastRebuild: Instant = Instant.EPOCH
    private val unknownKids = LinkedHashMap<String, Instant>()

    init {
        require(audiences.isNotEmpty()) { "A social identity verifier needs at least one accepted client id" }
    }

    /**
     * The verified identity, or [AuthException] `INVALID_SOCIAL_TOKEN` for any token that fails any
     * check (`SOCIAL_SIGN_IN_TEMPORARILY_UNAVAILABLE` when the provider's keys can't be fetched).
     */
    suspend fun verify(
        idToken: String,
        expectedNonce: String,
    ): VerifiedSocialIdentity {
        val token = idToken.trim()
        val nonce = expectedNonce.trim()
        if (token.isEmpty() || token.length > MAX_TOKEN_LENGTH || nonce.isEmpty()) throw invalid("blank or oversized")
        val signature = withContext(Dispatchers.IO) { verifySignature(token) }
        return checkClaims(signature, nonce)
    }

    private fun verifySignature(token: String): JsonWebSignature {
        try {
            return tokenVerifier.get().verify(token)
        } catch (error: TokenVerifier.VerificationException) {
            val kid = if (error.isUnknownKey()) keyIdOf(token) else null
            if (kid != null && mayRebuildFor(kid)) {
                tokenVerifier.set(buildTokenVerifier())
                return try {
                    tokenVerifier.get().verify(token)
                } catch (retryError: TokenVerifier.VerificationException) {
                    if (retryError.isUnknownKey()) rememberUnknown(kid)
                    throw translate(retryError)
                }
            }
            throw translate(error)
        } catch (error: RuntimeException) {
            throw invalid(error.javaClass.simpleName)
        }
    }

    private fun checkClaims(
        signature: JsonWebSignature,
        expectedNonce: String,
    ): VerifiedSocialIdentity {
        val algorithm = signature.header.algorithm.orEmpty()
        if (algorithm !in allowedAlgorithms(provider)) throw invalid("algorithm")
        val payload = signature.payload
        if (payload.issuer.orEmpty() !in allowedIssuers(provider)) throw invalid("issuer")

        val audience = payload.audienceAsList.orEmpty()
        if (audience.size != 1 || audience.single() !in audiences) throw invalid("audience")

        val nowSeconds = clock.instant().epochSecond
        val expiresAt = payload.expirationTimeSeconds ?: throw invalid("no exp")
        if (expiresAt <= nowSeconds) throw invalid("expired")
        val issuedAt = payload.issuedAtTimeSeconds ?: throw invalid("no iat")
        if (issuedAt > nowSeconds + MAX_FUTURE_SKEW.seconds) throw invalid("iat in the future")

        val subject = payload.subject?.trim().orEmpty()
        if (subject.isEmpty() || subject.length > MAX_SUBJECT_LENGTH) throw invalid("subject")

        val nonceClaim = payload["nonce"] as? String ?: throw invalid("no nonce")
        if (!nonceMatches(nonceClaim, expectedNonce)) throw invalid("nonce")

        val email = (payload["email"] as? String)?.let(::normalizeEmail)?.takeIf { it.contains('@') && it.length <= MAX_EMAIL_LENGTH }
        val emailVerified = payload["email_verified"].asClaimBoolean()
        val privateRelay =
            provider == SocialProvider.APPLE &&
                (payload["is_private_email"].asClaimBoolean() || email?.endsWith(APPLE_RELAY_DOMAIN) == true)
        val authoritative =
            when (provider) {
                SocialProvider.GOOGLE -> email != null && emailVerified && (email.endsWith("@gmail.com") || !(payload["hd"] as? String).isNullOrBlank())
                SocialProvider.APPLE -> email != null && emailVerified && !privateRelay
            }
        return VerifiedSocialIdentity(
            provider = provider,
            subject = subject,
            audience = audience.single(),
            email = email,
            emailVerified = emailVerified,
            privateRelay = privateRelay,
            authoritativeEmail = email.takeIf { authoritative },
        )
    }

    private fun nonceMatches(
        claim: String,
        expected: String,
    ): Boolean {
        if (constantTimeEquals(claim, expected)) return true
        return provider == SocialProvider.APPLE && constantTimeEquals(claim.lowercase(), sha256Hex(expected))
    }

    /** A refresh for [kid]: not while it is on the negative list, and at most once per interval. */
    private fun mayRebuildFor(kid: String): Boolean {
        val now = clock.instant()
        synchronized(this) {
            unknownKids[kid]?.let { listedUntil ->
                if (now.isBefore(listedUntil)) return false
                unknownKids.remove(kid)
            }
            if (now.isBefore(lastRebuild.plus(keyRefreshMinInterval))) return false
            lastRebuild = now
            return true
        }
    }

    private fun rememberUnknown(kid: String) {
        val until = clock.instant().plus(UNKNOWN_KID_TTL)
        synchronized(this) {
            unknownKids.remove(kid)
            unknownKids[kid] = until
            while (unknownKids.size > MAX_UNKNOWN_KIDS) unknownKids.remove(unknownKids.keys.first())
        }
    }

    /** The header's `kid`, or null for a token whose header can't be read (then it is just rejected). */
    private fun keyIdOf(token: String): String? =
        runCatching {
            val header = java.util.Base64.getUrlDecoder().decode(token.substringBefore('.'))
            kotlinx.serialization.json.Json
                .parseToJsonElement(header.toString(Charsets.UTF_8))
                .let { it as? kotlinx.serialization.json.JsonObject }
                ?.get("kid")
                ?.let { it as? kotlinx.serialization.json.JsonPrimitive }
                ?.takeIf { it.isString }
                ?.content
                ?.takeIf { it.isNotEmpty() && it.length <= MAX_KID_LENGTH }
        }.getOrNull()

    private fun buildTokenVerifier(): TokenVerifier {
        val builder =
            TokenVerifier
                .newBuilder()
                .setCertificatesLocation(jwksUrl)
                .setClock { clock.millis() }
        httpTransportFactory?.let(builder::setHttpTransportFactory)
        return builder.build()
    }

    private fun TokenVerifier.VerificationException.isUnknownKey(): Boolean = message?.startsWith("Could not find PublicKey") == true

    private fun translate(error: TokenVerifier.VerificationException): AuthException =
        if (error.message?.startsWith("Error fetching PublicKey") == true) {
            logger.warn("{} sign-in keys could not be fetched ({})", provider.wire, error.cause?.javaClass?.simpleName)
            AuthException("SOCIAL_SIGN_IN_TEMPORARILY_UNAVAILABLE")
        } else {
            invalid(error.message?.take(MAX_REASON_LENGTH) ?: "verification failed")
        }

    // The reason is a fixed phrase or the library's own message (never contains the token or claims).
    private fun invalid(reason: String): AuthException {
        logger.debug("{} ID token rejected: {}", provider.wire, reason)
        return AuthException("INVALID_SOCIAL_TOKEN")
    }

    companion object {
        private val logger = LoggerFactory.getLogger(SocialIdentityVerifier::class.java)

        const val GOOGLE_JWKS_URL = "https://www.googleapis.com/oauth2/v3/certs"
        const val APPLE_JWKS_URL = "https://appleid.apple.com/auth/keys"
        const val APPLE_RELAY_DOMAIN = "@privaterelay.appleid.com"
        val MAX_FUTURE_SKEW: Duration = Duration.ofMinutes(5)
        private const val MAX_TOKEN_LENGTH = 16_384
        private const val MAX_SUBJECT_LENGTH = 255
        private const val MAX_EMAIL_LENGTH = 320
        private const val MAX_REASON_LENGTH = 80
        private const val MAX_KID_LENGTH = 128
        const val MAX_UNKNOWN_KIDS = 256
        val UNKNOWN_KID_TTL: Duration = Duration.ofMinutes(10)

        fun defaultJwksUrl(provider: SocialProvider): String =
            when (provider) {
                SocialProvider.GOOGLE -> GOOGLE_JWKS_URL
                SocialProvider.APPLE -> APPLE_JWKS_URL
            }

        fun allowedIssuers(provider: SocialProvider): Set<String> =
            when (provider) {
                SocialProvider.GOOGLE -> setOf("https://accounts.google.com", "accounts.google.com")
                SocialProvider.APPLE -> setOf("https://appleid.apple.com")
            }

        fun allowedAlgorithms(provider: SocialProvider): Set<String> =
            when (provider) {
                SocialProvider.GOOGLE -> setOf("RS256")
                SocialProvider.APPLE -> setOf("RS256", "ES256")
            }

        fun sha256Hex(value: String): String =
            MessageDigest
                .getInstance("SHA-256")
                .digest(value.toByteArray(Charsets.UTF_8))
                .joinToString("") { byte -> "%02x".format(byte) }

        private fun constantTimeEquals(
            a: String,
            b: String,
        ): Boolean = MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))
    }
}

/** Apple sends booleans as JSON booleans or as the strings "true"/"false"; Google as booleans. */
internal fun Any?.asClaimBoolean(): Boolean =
    when (this) {
        is Boolean -> this
        is String -> trim().equals("true", ignoreCase = true)
        else -> false
    }

/** `jane@gmail.com` -> `j•••@gmail.com`. Never more than the first character of the local part. */
internal fun maskEmail(email: String): String {
    val at = email.lastIndexOf('@')
    if (at <= 0) return "•••"
    return "${email.first()}•••${email.substring(at)}"
}
