package com.pynapli.shared.network

import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.dto.CurrentLocationDto
import com.pynapli.shared.network.dto.UpdateCurrentLocationRequestDto

/**
 * Location slice of the API surface, split out of the monolithic [PynapliApiClient]
 * (which now composes the per-feature interfaces). Covers the current-location update.
 * Signatures are unchanged.
 */
interface LocationApi {
    suspend fun updateCurrentLocation(request: UpdateCurrentLocationRequestDto): AppResult<CurrentLocationDto>

    /**
     * `POST /v1/presence/heartbeat` (204, no body): "this member has the app open" (plan G2). Sent
     * about once a minute while the app is in front and signed in; never retried.
     */
    suspend fun sendPresenceHeartbeat(): AppResult<Unit>
}
