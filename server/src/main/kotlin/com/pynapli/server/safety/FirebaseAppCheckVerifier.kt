package com.pynapli.server.safety

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
    private val verifyToken: (String) -> Unit,
) : AppCheckVerifier {
    override suspend fun verify(token: String?): Boolean {
        if (token.isNullOrBlank()) return false
        return withContext(Dispatchers.IO) {
            runCatching { verifyToken(token) }
                .onFailure { error -> logger.debug("App Check token rejected: {}", error.javaClass.simpleName) }
                .isSuccess
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(FirebaseAppCheckVerifier::class.java)

        private const val ISSUER_PREFIX = "https://firebaseappcheck.googleapis.com/"
        private const val JWKS_URL = "https://firebaseappcheck.googleapis.com/v1/jwks"

        /**
         * Builds a verifier for [projectNumber] (the numeric Firebase project number, e.g.
         * "209241465883"): issuer `https://firebaseappcheck.googleapis.com/<number>`, audience
         * `projects/<number>`, keys from the App Check JWKS endpoint.
         */
        fun forProject(projectNumber: String): FirebaseAppCheckVerifier {
            val tokenVerifier =
                TokenVerifier
                    .newBuilder()
                    .setIssuer("$ISSUER_PREFIX$projectNumber")
                    .setAudience("projects/$projectNumber")
                    .setCertificatesLocation(JWKS_URL)
                    .build()
            return FirebaseAppCheckVerifier { token -> tokenVerifier.verify(token) }
        }
    }
}
