package com.pynapli.shared.network

import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.dto.NotificationPreferencesDto
import com.pynapli.shared.network.dto.PushDeviceRegistrationResponseDto
import com.pynapli.shared.network.dto.RegisterPushDeviceRequestDto
import com.pynapli.shared.network.dto.UnregisterPushDeviceRequestDto
import com.pynapli.shared.network.dto.UpdateNotificationPreferencesRequestDto
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType

/** Ktor implementation of [NotificationApi], composed into `KtorPynapliApiClient` via delegation. */
internal class KtorNotificationApi(
    private val support: KtorApiSupport,
) : NotificationApi {
    override suspend fun getNotificationPreferences(): AppResult<NotificationPreferencesDto> = support.authGet("/v1/notifications/preferences")

    override suspend fun updateNotificationPreferences(
        request: UpdateNotificationPreferencesRequestDto,
    ): AppResult<NotificationPreferencesDto> = support.authSend(HttpMethod.Patch, "/v1/notifications/preferences", request)

    override suspend fun registerPushDevice(
        request: RegisterPushDeviceRequestDto,
    ): AppResult<PushDeviceRegistrationResponseDto> = support.authSend(HttpMethod.Post, "/v1/notifications/devices", request)

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
