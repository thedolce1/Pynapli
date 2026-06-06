package com.pynapli.shared.network

import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.dto.PresenceHeartbeatResponseDto

/**
 * Presence slice of the API surface, split out of the monolithic [PynapliApiClient]
 * (which now composes the per-feature interfaces). Covers the presence heartbeat.
 * Signatures are unchanged.
 */
interface PresenceApi {
    suspend fun heartbeatPresence(): AppResult<PresenceHeartbeatResponseDto>
}
