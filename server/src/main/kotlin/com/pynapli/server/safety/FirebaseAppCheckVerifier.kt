package com.pynapli.server.safety

import com.google.api.client.json.webtoken.JsonWebSignature
import com.google.auth.oauth2.TokenVerifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

/**
 * Cryptographically verifies a Firebase App Check token: an RS256 JWT minted by Google, validated
 * against the App Check public keys (JWKS) for the project, with issuer / audience / expiry checks.
 * Reuses `com.google.auth.oauth2.TokenVerifier` (already used for the Play RTDN OIDC check) — no new
 * dependency, no hand-rolled crypto.
 *
 * The token-verify step is injected so the wrapper contract (reject null/blank, treat any verifier
 * exception as "not attested") is unit-testable without network access or real tokens. Use
 * [forProject] to build the production verifier for a Firebase project number.
 */
class FirebaseAppCheckVerifier(
    private val verifyToken: (String) -> VerifiedAppCheckClaims,
) : AppCheckVerifier {
    override suspend fun verify(token: String?): Boolean {
        if (token.isNullOrBlank()) return false
        return withContext(Dispatchers.IO) {
            runCatching { verifyToken(token).isValidAppCheckToken() }
                .onFailure { error -> logger.debug("App Check token rejected: {}", error.javaClass.simpleName) }
                .getOrDefault(false)
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(FirebaseAppCheckVerifier::class.java)

        private const val ISSUER_PREFIX = "https://firebaseappcheck.googleapis.com/"
        private const val JWKS_URL = "https://firebaseappcheck.googleapis.com/v1/jwks"

        /**
         * Builds a verifier for [projectNumber] (the numeric Firebase project number, e.g.
         * "209241465883"): issuer `https://firebaseappcheck.googleapis.com/<number>`, audience
         * containing `projects/<number>`, non-empty subject, keys from the App Check JWKS endpoint.
         *
         * Google auth's [TokenVerifier] compares `aud` by equality when configured on the builder,
         * but Firebase App Check follows the JWT spec and can return `aud` as a list. Let
         * [TokenVerifier] handle signature / issuer / expiry, then validate App Check-specific
         * claims explicitly.
         */
        fun forProject(projectNumber: String): FirebaseAppCheckVerifier {
            val expectedAudience = "projects/$projectNumber"
            val tokenVerifier =
                TokenVerifier
                    .newBuilder()
                    .setIssuer("$ISSUER_PREFIX$projectNumber")
                    .setCertificatesLocation(JWKS_URL)
                    .build()
            return FirebaseAppCheckVerifier { token ->
                tokenVerifier
                    .verify(token)
                    .toVerifiedAppCheckClaims(expectedAudience)
            }
        }
    }
}

data class VerifiedAppCheckClaims(
    val audiences: List<String>,
    val subject: String?,
    val expectedAudience: String,
)

internal fun JsonWebSignature.toVerifiedAppCheckClaims(expectedAudience: String): VerifiedAppCheckClaims =
    VerifiedAppCheckClaims(
        audiences = payload.audienceAsList,
        subject = payload.subject,
        expectedAudience = expectedAudience,
    )

internal fun VerifiedAppCheckClaims.isValidAppCheckToken(): Boolean = expectedAudience in audiences && !subject.isNullOrBlank()
