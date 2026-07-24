package com.pynapli.shared.network

/**
 * Exchanges the stored refresh token for a fresh access token and persists the rotated session.
 *
 * Access tokens live 15 minutes (server `AUTH_ACCESS_TOKEN_TTL`), so without this every client
 * request starts failing 15 minutes after sign-in, and a relaunch after that window looked to the
 * app like a terminal auth failure — it signed the user out even though they never logged out.
 *
 * Implemented in the auth layer (it owns the session store); consumed by the HTTP client's
 * refresh-on-401 interceptor, which is why the contract lives here rather than in `auth`.
 *
 * Implementations MUST be single-flight: the server rotates the refresh token on every call
 * (`AuthSessionManager.refresh`), so two concurrent refreshes with the same token would leave one
 * of them holding a token the server has already invalidated.
 */
fun interface SessionRefresher {
    /**
     * @param expiredAccessToken the token the failing request actually sent. It is what makes
     *   single-flight possible: a caller whose token is no longer the stored one has been overtaken
     *   by another refresh and must reuse that result rather than rotate again. Null means "refresh
     *   regardless", used only where no token was in play.
     * @return the new access token, or null when the session cannot be refreshed (sign the user out).
     */
    suspend fun refreshAccessToken(expiredAccessToken: String?): String?
}

/**
 * Late-bound [SessionRefresher]. The HTTP client has to exist before the auth layer that knows how
 * to refresh can be constructed, so a graph installs the interceptor with this holder up front and
 * sets [delegate] once the auth layer is built. Until then it reports "cannot refresh", which is the
 * safe answer.
 */
class SessionRefresherHolder : SessionRefresher {
    var delegate: SessionRefresher? = null

    override suspend fun refreshAccessToken(expiredAccessToken: String?): String? = delegate?.refreshAccessToken(expiredAccessToken)
}

/** Path suffix of the refresh endpoint; the interceptor must never try to refresh this call itself. */
internal const val AUTH_REFRESH_PATH = "/v1/auth/refresh"
