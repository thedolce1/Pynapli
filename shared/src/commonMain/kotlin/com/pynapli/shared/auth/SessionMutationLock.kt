package com.pynapli.shared.auth

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One per [SessionStore], shared by [StoredSessionRefresher] and [AuthRepository]. The refresher
 * holds it across read, refresh and save; sign-out holds it to clear. Without it a refresh that was
 * on the wire when the member signed out saved its rotated session after the clear, and the member
 * was signed back in. Each shell builds one and hands the same instance to both.
 *
 * Never hold it across a call that can itself need a refresh (any authenticated request): the
 * refresher would wait on it forever.
 */
class SessionMutationLock {
    private val mutex = Mutex()

    suspend fun <T> withLock(block: suspend () -> T): T = mutex.withLock { block() }
}
