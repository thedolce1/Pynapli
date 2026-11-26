package com.pynapli.app.grid

import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Chip taps and filter applies (G2-2 ticket 3): only the newest one reaches the server.
 *
 * Each tap runs in its own coroutine (the screen launches one per tap), so this tracks the caller's
 * own [Job]: a newer [run] cancels the older one, whether it is still waiting out the debounce (then
 * it never sends) or already on the wire (the HTTP call is cancelled with it). A debounced [run]
 * waits [debounce] first, so three quick taps send one request with the final filters. The feed
 * generation guard in [GridStateHolder] still drops any answer that lands after a newer load.
 *
 * Main-thread only, like the holder's state, so a plain field is enough.
 */
internal class LatestFilterRequest(
    private val debounce: Duration = CHIP_DEBOUNCE,
) {
    private var current: Job? = null

    suspend fun run(
        debounced: Boolean,
        block: suspend () -> Unit,
    ) {
        val mine = currentCoroutineContext()[Job]
        val previous = current
        current = mine
        // Never cancel ourselves or a coroutine we run inside (a test calling twice in a row, say).
        if (previous != null && previous !== mine && (mine == null || !previous.isAncestorOf(mine))) previous.cancel()
        try {
            if (debounced) delay(debounce)
            block()
        } finally {
            if (current === mine) current = null
        }
    }

    private fun Job.isAncestorOf(job: Job): Boolean = children.any { it === job || it.isAncestorOf(job) }

    companion object {
        /** Long enough to fold a burst of taps into one request, short enough not to feel late. */
        val CHIP_DEBOUNCE: Duration = 150.milliseconds
    }
}
