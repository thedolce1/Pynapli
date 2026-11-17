package com.pynapli.app

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Brings the app back to canonical HTTP state when it returns to the foreground (CLAUDE.md:
 * WebSocket events are convenience updates, HTTP is canonical, refetch on resume). While the app was
 * away the realtime sockets were closed (Android/iOS) or the tab was hidden (web), so any surface may
 * be stale. [refreshAll] is the list of silent refetches ([PynapliCommonGraph] builds it).
 *
 * Bursts are coalesced: foreground signals within [minInterval] of the last refresh are ignored
 * (Android fires onStart after permission dialogs and share sheets; browsers fire visibilitychange
 * on every tab switch).
 */
class AppResumeRefresher(
    private val refreshAll: suspend () -> Unit,
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val minInterval: Duration = 10.seconds,
) {
    private var lastRefresh: TimeMark? = null

    /** Call when the app comes back to the foreground. Signed out, it does nothing. */
    suspend fun onForeground(signedIn: Boolean) {
        if (!signedIn) return
        val last = lastRefresh
        if (last != null && last.elapsedNow() < minInterval) return
        lastRefresh = timeSource.markNow()
        refreshAll()
    }
}
