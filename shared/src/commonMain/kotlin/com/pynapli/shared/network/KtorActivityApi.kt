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
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.http.HttpMethod

/** Ktor implementation of [ActivityApi], composed into `KtorPynapliApiClient` via delegation. */
internal class KtorActivityApi(
    private val support: KtorApiSupport,
) : ActivityApi {
    override suspend fun listActivity(
        limit: Int?,
        tab: String?,
        cursor: String?,
    ): AppResult<ActivityFeedResponseDto> =
        support.authGet("/v1/activity") {
            limit?.let { parameter("limit", it) }
            tab?.let { parameter("tab", it) }
            cursor?.let { parameter("cursor", it) }
        }

    override suspend fun getActivitySummary(): AppResult<ActivitySummaryDto> = support.authGet("/v1/activity/summary")

    override suspend fun markActivitySeen(
        request: MarkActivitySeenRequestDto,
    ): AppResult<MarkActivitySeenResponseDto> = support.authSend(HttpMethod.Post, "/v1/activity/seen", request)

    override suspend fun dismissActivityEvent(eventId: String): AppResult<DismissActivityEventResponseDto> = support.authPost("/v1/activity/$eventId/dismiss")

    override suspend fun sendFlirt(request: SendFlirtRequestDto): AppResult<SendFlirtResponseDto> = support.authSend(HttpMethod.Post, "/v1/activity/flirts", request)

    override suspend fun requestAlbumAccess(
        request: RequestAlbumAccessRequestDto,
    ): AppResult<RequestAlbumAccessResponseDto> = support.authSend(HttpMethod.Post, "/v1/activity/album-requests", request)

    override suspend fun respondToAlbumAccessRequest(
        eventId: String,
        request: RespondAlbumAccessRequestDto,
    ): AppResult<RespondAlbumAccessResponseDto> = support.authSend(HttpMethod.Post, "/v1/activity/album-requests/$eventId/respond", request)
}
