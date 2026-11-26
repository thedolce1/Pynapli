package com.pynapli.server.auth.social

import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import java.util.UUID

/** An Apple refresh token as stored: sealed under [SocialTokenSeal] for its identity row. */
class SealedAppleToken(
    val identityId: UUID,
    val clientId: String,
    val sealed: ByteArray,
)

/**
 * Revokes Apple grants when the member deletes the account or unlinks Apple (Apple requires apps that
 * offer account deletion to revoke the user's tokens). Runs after the database change committed, off
 * the request path ([launch]); best-effort: a failure is logged by status/exception class only and not
 * retried, because by then the token is already scrubbed from the database (ONBOARDING_AUTH_SPEC,
 * "Social sign-in", residuals).
 */
class AppleGrantRevoker(
    private val client: AppleTokenClient,
    private val seal: SocialTokenSeal,
    private val launch: (suspend () -> Unit) -> Unit,
) {
    fun revokeLater(tokens: List<SealedAppleToken>) {
        if (tokens.isEmpty()) return
        launch { revokeNow(tokens) }
    }

    /** Returns how many Apple accepted. */
    suspend fun revokeNow(tokens: List<SealedAppleToken>): Int {
        var revoked = 0
        for (token in tokens) {
            val refreshToken = seal.open(token.identityId, token.sealed)
            if (refreshToken == null) {
                logger.warn("A stored Apple token could not be opened; not revoked")
                continue
            }
            try {
                if (client.revoke(token.clientId, refreshToken)) revoked++
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                logger.warn("Apple token revocation failed ({})", error.javaClass.simpleName)
            }
        }
        return revoked
    }

    private companion object {
        private val logger = LoggerFactory.getLogger(AppleGrantRevoker::class.java)
    }
}
