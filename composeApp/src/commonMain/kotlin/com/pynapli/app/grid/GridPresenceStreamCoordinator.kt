package com.pynapli.app.grid

import com.pynapli.shared.network.PresenceStreamClient
import kotlinx.coroutines.flow.collect

class GridPresenceStreamCoordinator(
    private val client: PresenceStreamClient,
    private val gridStateHolder: GridStateHolder
) {
    suspend fun run() {
        client.connect(gridStateHolder.visibleUserIds).collect { delta ->
            gridStateHolder.applyPresenceDelta(delta)
        }
    }
}
