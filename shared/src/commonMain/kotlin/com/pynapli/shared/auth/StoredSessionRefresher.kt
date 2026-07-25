package com.pynapli.shared.auth

import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.AuthApi
import com.pynapli.shared.network.SessionRefresher
import com.pynapli.shared.network.dto.RefreshSessionRequestDto
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * [SessionRefresher] backed by the stored session: swaps the persisted refresh token for a new
 * session and saves it, so the [SessionStore] (which is also the app's `AccessTokenProvider`)
 * immediately hands the new access token to every subsequent request.
 *
 * Single-flight via [mutex], and deliberately more than just a lock. The server *rotates* the
 * refresh token on each call, so when several requests 401 at once only the first may actually
 * refresh; the rest would otherwise present a token the server just invalidated and get signed out.
 * Waiters therefore re-read the store after acquiring the lock and, if the access token has already
 * moved on, return that instead of refreshing again.
 */
class StoredSessionRefresher(
    private val authApi: AuthApi,
    private val sessionStore: SessionStore,
    // Notified when a refresh is *terminally* rejected, so the app can return to sign-in instead of
    // looping on a dead session. Null where no observer exists (e.g. unit tests that only assert the
    // token result).
    private val invalidationBus: SessionInvalidationBus? = null,
) : SessionRefresher {
    private val mutex = Mutex()

    override suspend fun refreshAccessToken(expiredAccessToken: String?): String? =
        mutex.withLock {
            val current = sessionStore.currentSession() ?: return@withLock null
            if (expiredAccessToken != null && current.accessToken != expiredAccessToken) {
                // Someone already rotated the session out from under this caller. Hand back the
                // live token; refreshing again would present a refresh token the server just
                // invalidated and sign the user out.
                return@withLock current.accessToken
            }
            when (val result = authApi.refreshSession(RefreshSessionRequestDto(refreshToken = current.refreshToken))) {
                is AppResult.Success -> {
                    sessionStore.save(result.value)
                    sessionStore.currentSession()?.accessToken
                }

                is AppResult.Failure -> {
                    // A terminal rejection means the session is dead — revoked, or the account is no
                    // longer active. Clear it and signal the app to drop back to sign-in. A transient
                    // failure (network, 5xx) leaves the session intact so a later retry can succeed.
                    if (result.error.code in terminalAuthErrorCodes) {
                        sessionStore.clear()
                        invalidationBus?.notifyInvalidated()
                    }
                    null
                }
            }
        }
}
