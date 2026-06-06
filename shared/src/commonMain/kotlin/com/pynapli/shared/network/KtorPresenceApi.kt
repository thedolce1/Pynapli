package com.pynapli.shared.network

import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.dto.PresenceHeartbeatResponseDto
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post

/** Ktor implementation of [PresenceApi], composed into `KtorPynapliApiClient` via delegation. */
internal class KtorPresenceApi(
    private val support: KtorApiSupport,
) : PresenceApi {
    override suspend fun heartbeatPresence(): AppResult<PresenceHeartbeatResponseDto> =
        support.authenticated { token ->
            support.httpClient
                .post(support.endpoint("/v1/presence/heartbeat")) {
                    bearerAuth(token)
                }.toAppResult(support.json)
        }
}
