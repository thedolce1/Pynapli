package com.pynapli.shared.auth

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Signals that the current session has become terminally invalid *mid-use* — the refresh token was
 * revoked (logout on another device, admin action) or the account is no longer active (moderation
 * ban). Without this the app would sit in an authenticated shell where every request 401s, and only
 * a force-quit + relaunch (which re-runs launch-time validation) would return the user to sign-in.
 *
 * Emitted by [StoredSessionRefresher] when a refresh is *terminally* rejected — never on a transient
 * network/5xx failure — and observed by the auth state holder, which drops back to the sign-in
 * screen.
 */
class SessionInvalidationBus {
    // extraBufferCapacity = 1 so a terminal signal is not dropped if it fires in the instant between
    // the observer detaching and re-attaching; the event matters only while authenticated, and the
    // app observes it for that whole window.
    private val _events = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val events: SharedFlow<Unit> = _events.asSharedFlow()

    fun notifyInvalidated() {
        _events.tryEmit(Unit)
    }
}
