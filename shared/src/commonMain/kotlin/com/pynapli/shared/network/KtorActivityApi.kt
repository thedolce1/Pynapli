package com.pynapli.shared.network

import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.dto.ActivityFeedResponseDto
import com.pynapli.shared.network.dto.ActivitySummaryDto
import com.pynapli.shared.network.dto.DismissActivityEventResponseDto
import com.pynapli.shared.network.dto.MarkActivitySeenRequestDto
import com.pynapli.shared.network.dto.MarkActivitySeenResponseDto
import com.pynapli.shared.network.dto.RequestAlbumAccessRequestDto
import com.pynapli.shared.network.dto.RequestAlbumAccessResponseDto
import com.pynapli.shared.network.dto.RespondAlbumAccessRequestDto
import com.pynapli.shared.network.dto.RespondAlbumAccessResponseDto
import com.pynapli.shared.network.dto.SendFlirtRequestDto
import com.pynapli.shared.network.dto.SendFlirtResponseDto
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType

/** Ktor implementation of [ActivityApi], composed into `KtorPynapliApiClient` via delegation. */
internal class KtorActivityApi(
    private val support: KtorApiSupport,
) : ActivityApi {
    override suspend fun listActivity(
        limit: Int?,
        tab: String?,
        cursor: String?,
    ): AppResult<ActivityFeedResponseDto> =
        support.authenticated { token ->
            support.httpClient
                .get(support.endpoint("/v1/activity")) {
                    bearerAuth(token)
                    limit?.let { parameter("limit", it) }
                    tab?.let { parameter("tab", it) }
                    cursor?.let { parameter("cursor", it) }
                }.toAppResult(support.json)
        }

    override suspend fun getActivitySummary(): AppResult<ActivitySummaryDto> =
        support.authenticated { token ->
            support.httpClient
                .get(support.endpoint("/v1/activity/summary")) {
                    bearerAuth(token)
                }.toAppResult(support.json)
        }

    override suspend fun markActivitySeen(
        request: MarkActivitySeenRequestDto,
    ): AppResult<MarkActivitySeenResponseDto> =
        support.authenticated { token ->
            support.httpClient
                .post(support.endpoint("/v1/activity/seen")) {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }.toAppResult(support.json)
        }

    override suspend fun dismissActivityEvent(eventId: String): AppResult<DismissActivityEventResponseDto> =
        support.authenticated { token ->
            support.httpClient
                .post(support.endpoint("/v1/activity/$eventId/dismiss")) {
                    bearerAuth(token)
                }.toAppResult(support.json)
        }

    override suspend fun sendFlirt(request: SendFlirtRequestDto): AppResult<SendFlirtResponseDto> =
        support.authenticated { token ->
            support.httpClient
                .post(support.endpoint("/v1/activity/flirts")) {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }.toAppResult(support.json)
        }

    override suspend fun requestAlbumAccess(
        request: RequestAlbumAccessRequestDto,
    ): AppResult<RequestAlbumAccessResponseDto> =
        support.authenticated { token ->
            support.httpClient
                .post(support.endpoint("/v1/activity/album-requests")) {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }.toAppResult(support.json)
        }

    override suspend fun respondToAlbumAccessRequest(
        eventId: String,
        request: RespondAlbumAccessRequestDto,
    ): AppResult<RespondAlbumAccessResponseDto> =
        support.authenticated { token ->
            support.httpClient
                .post(support.endpoint("/v1/activity/album-requests/$eventId/respond")) {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }.toAppResult(support.json)
        }
}
