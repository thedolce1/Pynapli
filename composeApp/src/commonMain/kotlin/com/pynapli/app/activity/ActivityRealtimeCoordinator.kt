package com.pynapli.app.activity

import com.pynapli.shared.network.ActivityStreamClient

class ActivityRealtimeCoordinator(
    private val client: ActivityStreamClient,
    private val activityStateHolder: ActivityStateHolder,
) {
    suspend fun run() {
        val refresh: suspend () -> Unit = {
            activityStateHolder.refreshSummarySilently()
            activityStateHolder.refreshSilently()
        }
        // Back after a drop: anything that happened meanwhile had no delta, so refetch.
        client.connect(onReconnected = refresh).collect {
            activityStateHolder.refreshSummarySilently()
            activityStateHolder.refreshSilently()
        }
    }
}
