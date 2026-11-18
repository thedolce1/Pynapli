package com.pynapli.app.activity

import com.pynapli.shared.network.ActivityStreamClient

class ActivityRealtimeCoordinator(
    private val client: ActivityStreamClient,
    private val activityStateHolder: ActivityStateHolder,
    /** A match delta (either side of a new match): refetch the unseen "It's a match" moments. */
    private val onMatch: suspend () -> Unit = {},
) {
    suspend fun run() {
        val refresh: suspend () -> Unit = {
            activityStateHolder.refreshSummarySilently()
            activityStateHolder.refreshSilently()
            onMatch()
        }
        // Back after a drop: anything that happened meanwhile had no delta, so refetch.
        client.connect(onReconnected = refresh).collect { delta ->
            activityStateHolder.refreshSummarySilently()
            activityStateHolder.refreshSilently()
            if (delta.type == MATCH_DELTA_TYPE) onMatch()
        }
    }

    private companion object {
        const val MATCH_DELTA_TYPE = "match"
    }
}