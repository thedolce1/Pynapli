package com.pynapli.shared.auth

import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.AuthApi
import com.pynapli.shared.network.SessionRefresher
import com.pynapli.shared.network.dto.RefreshSessionRequestDto
import kotlinx.coroutines.delay

/**
 * [SessionRefresher] backed by the stored session: swaps the persisted refresh token for a new
 * session and saves it, so the [SessionStore] (which is also the app's `AccessTokenProvider`)
 * immediately hands the new access token to every subsequent request.
 *
 * Single-flight via [lock], and deliberately more than just a lock. The server *rotates* the
 * refresh token on each call, so when several requests 401 at once only the first may actually
 * refresh; the rest would otherwise present a token the server just invalidated and get signed out.
 * Waiters therefore re-read the store after acquiring the lock and, if the access token has already
 * moved on, return that instead of refreshing again. Across processes sharing one store (web tabs)
 * the mutex cannot help, so a terminal rejection re-reads the store before signing out.
 */
class StoredSessionRefresher(
    private val authApi: AuthApi,
    private val sessionStore: SessionStore,
    // Notified when a refresh is *terminally* rejected, so the app can return to sign-in instead of
    // looping on a dead session. Null where no observer exists (e.g. unit tests that only assert the
    // token result).
    private val invalidationBus: SessionInvalidationBus? = null,
    // Only for stores shared across processes (web tabs on one localStorage). The tab that loses a
    // refresh race usually hears its rejection *before* the winning tab has saved the rotated
    // session, so a terminal rejection re-reads the store up to [settleAttempts] more times,
    // [settleDelayMillis] apart, before concluding the session is dead. The default (no settling)
    // keeps single-process stores signing out immediately.
    private val settleAttempts: Int = 0,
    private val settleDelayMillis: Long = 0,
    // Shared with this store's AuthRepository, so sign-out's clear waits for a refresh in flight
    // instead of being overwritten by it. A private one by default (tests, single-holder callers).
    private val lock: SessionMutationLock = SessionMutationLock(),
) : SessionRefresher {
    override suspend fun refreshAccessToken(expiredAccessToken: String?): String? =
        lock.withLock {
            val current = sessionStore.currentSession() ?: return@withLock null
            if (expiredAccessToken != null && current.accessToken != expiredAccessToken) {
                // Someone already rotated the session out from under this caller. Hand back the
                // live token; refreshing again would present a refresh token the server just
                // invalidated and sign the user out.
                return@withLock current.accessToken
            }
            when (val result = authApi.refreshSession(RefreshSessionRequestDto(refreshToken = current.refreshToken))) {
                is AppResult.Success -> {
                    // Save only over the session this refresh started from. Emptied meanwhile (signed
                    // out in another tab, which this process's lock cannot see) or replaced (another
                    // tab rotated it, or someone else signed in): the answer is stale, so keeping it
                    // would undo that sign-out or overwrite the newer session.
                    val latest = sessionStore.currentSession()
                    if (latest == null || latest.refreshToken != current.refreshToken) return@withLock latest?.accessToken
                    sessionStore.save(result.value)
                    sessionStore.currentSession()?.accessToken
                }

                is AppResult.Failure -> {
                    // A terminal rejection means the session is dead — revoked, or the account is no
                    // longer active. Clear it and signal the app to drop back to sign-in. A transient
                    // failure (network, 5xx) leaves the session intact so a later retry can succeed.
                    if (result.error.code !in terminalAuthErrorCodes) return@withLock null
                    // The mutex is per process, but some stores are shared across processes (web
                    // tabs share localStorage). The server lets exactly one caller exchange a refresh
                    // token, so a rejection may only mean another tab won the race and has already
                    // stored the rotated session. Re-read before clearing: only a rejection of the
                    // token that is *still* stored means the session is dead. (An emptied store —
                    // another tab signed out — is dead too, so it signals invalidation at once.)
                    var attempt = 0
                    while (true) {
                        val latest = sessionStore.currentSession() ?: break
                        if (latest.refreshToken != current.refreshToken) return@withLock latest.accessToken
                        if (attempt++ >= settleAttempts) break
                        delay(settleDelayMillis)
                    }
                    sessionStore.clear()
                    invalidationBus?.notifyInvalidated()
                    null
                }
            }
        }
}
