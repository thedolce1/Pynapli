package com.pynapli.app.activity

import com.pynapli.shared.network.ActivityStreamClient

class ActivityRealtimeCoordinator(
    private val client: ActivityStreamClient,
    private val activityStateHolder: ActivityStateHolder,
) {
    suspend fun run() {
        client.connect().collect {
            activityStateHolder.refreshSummarySilently()
            activityStateHolder.refreshSilently()
        }
    }
}
