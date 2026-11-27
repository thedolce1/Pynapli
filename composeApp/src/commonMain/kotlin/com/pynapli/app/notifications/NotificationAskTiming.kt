package com.pynapli.app.notifications

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * When it is worth asking for notification permission (ONBOARDING_AUTH_SPEC, Permissions): after the
 * member has had the first Grid in front of them, not during sign-up. [ready] turns true, once per
 * signed-in session, after [dwell] of Grid on screen while the app is in the foreground (time adds
 * up across tab switches; a backgrounded app does not count), or as soon as a conversation opens,
 * whichever comes first. Signing out resets it, so the next account's sign-up on this device never
 * inherits a reached moment. The shell still decides whether its OS needs a prompt at all, and asks
 * only once.
 */
class NotificationAskTiming(
    private val gridOnScreen: Flow<Boolean>,
    private val conversationOpen: Flow<Boolean>,
    private val appInForeground: Flow<Boolean>,
    private val signedIn: Flow<Boolean>,
    private val dwell: Duration = DEFAULT_GRID_DWELL,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    /** Grid time already seen in earlier stretches (before a tab switch or a trip to the background). */
    private var counted: Duration = Duration.ZERO

    /** Runs until cancelled. Launch once from the shell. */
    suspend fun run() {
        signedIn.distinctUntilChanged().collectLatest { isSignedIn ->
            if (isSignedIn) {
                awaitMoment()
            } else {
                // Signed out: the next account starts from nothing.
                _ready.value = false
                counted = Duration.ZERO
            }
        }
    }

    /** Returns once [ready] is true (at once if it already is). */
    private suspend fun awaitMoment() {
        if (_ready.value) return
        coroutineScope {
            val dwellJob =
                launch {
                    combine(gridOnScreen, appInForeground) { grid, foreground -> grid && foreground }
                        .distinctUntilChanged()
                        .collectLatest { counting ->
                            if (!counting) return@collectLatest
                            val started = timeSource.markNow()
                            try {
                                delay(dwell - counted)
                                _ready.value = true
                            } finally {
                                counted += started.elapsedNow()
                            }
                        }
                }
            val conversationJob =
                launch {
                    conversationOpen.first { open -> open }
                    _ready.value = true
                }
            _ready.first { ready -> ready }
            dwellJob.cancel()
            conversationJob.cancel()
        }
    }

    companion object {
        val DEFAULT_GRID_DWELL: Duration = 20.seconds
    }
}
