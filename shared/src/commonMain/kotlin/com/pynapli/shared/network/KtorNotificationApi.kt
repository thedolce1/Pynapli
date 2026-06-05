package com.pynapli.shared.network

import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.dto.NotificationPreferencesDto
import com.pynapli.shared.network.dto.PushDeviceRegistrationResponseDto
import com.pynapli.shared.network.dto.RegisterPushDeviceRequestDto
import com.pynapli.shared.network.dto.UnregisterPushDeviceRequestDto
import com.pynapli.shared.network.dto.UpdateNotificationPreferencesRequestDto
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType

/** Ktor implementation of [NotificationApi], composed into `KtorPynapliApiClient` via delegation. */
internal class KtorNotificationApi(
    private val support: KtorApiSupport,
) : NotificationApi {
    override suspend fun getNotificationPreferences(): AppResult<NotificationPreferencesDto> =
        support.authenticated { token ->
            support.httpClient
                .get(support.endpoint("/v1/notifications/preferences")) {
                    bearerAuth(token)
                }.toAppResult(support.json)
        }

    override suspend fun updateNotificationPreferences(
        request: UpdateNotificationPreferencesRequestDto,
    ): AppResult<NotificationPreferencesDto> =
        support.authenticated { token ->
            support.httpClient
                .patch(support.endpoint("/v1/notifications/preferences")) {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }.toAppResult(support.json)
        }

    override suspend fun registerPushDevice(
        request: RegisterPushDeviceRequestDto,
    ): AppResult<PushDeviceRegistrationResponseDto> =
        support.authenticated { token ->
            support.httpClient
                .post(support.endpoint("/v1/notifications/devices")) {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }.toAppResult(support.json)
        }

    override suspend fun unregisterPushDevice(request: UnregisterPushDeviceRequestDto): AppResult<Unit> =
        support.authenticated { token ->
            val response =
                support.httpClient.post(support.endpoint("/v1/notifications/devices/unregister")) {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }
            if (response.status.value in 200..299) {
                AppResult.Success(Unit)
            } else {
                response.toAppResult<Unit>(support.json)
            }
        }
}
