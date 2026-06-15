package com.pynapli.server.safety

/**
 * Attests that a request originated from a genuine, untampered instance of the Pynapli app by
 * verifying its Firebase App Check token. This is a transport-level anti-abuse boundary, distinct
 * from user authentication: it answers "is this our app?", not "who is the user?". Pairs with the
 * `configureAppCheck` plugin, which enforces it on state-changing requests.
 *
 * The cryptographic verifier (an App Check JWT validated against Google's public keys, keyed to the
 * Firebase project number) is a creds-gated production step. Until it is wired,
 * [HeaderPresenceAppCheckVerifier] enforces only that the attestation header is present and
 * [DisabledAppCheckVerifier] accepts everything.
 */
fun interface AppCheckVerifier {
    /** @return true when [token] attests a genuine app instance (or verification is disabled). */
    suspend fun verify(token: String?): Boolean
}

/** Accept-all verifier. Used when App Check enforcement is off (local/dev/test). */
object DisabledAppCheckVerifier : AppCheckVerifier {
    override suspend fun verify(token: String?): Boolean = true
}

/**
 * Staged boundary: requires a non-blank App Check header but does not yet verify it
 * cryptographically. Lets enforcement be switched on (once the client attaches the header) ahead of
 * the creds-gated cryptographic verifier. NOT a substitute for real verification.
 */
object HeaderPresenceAppCheckVerifier : AppCheckVerifier {
    override suspend fun verify(token: String?): Boolean = !token.isNullOrBlank()
}

/** Thrown by the App Check boundary when a request fails attestation. Mapped to 403. */
class AppCheckException(
    val code: String,
) : RuntimeException(code)
