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

                is AppResult.Failure -> null
            }
        }
}
