package com.pynapli.shared.network

import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.dto.BlockSummaryDto
import com.pynapli.shared.network.dto.BlockUserRequestDto
import com.pynapli.shared.network.dto.BlocksListDto
import com.pynapli.shared.network.dto.CreateReportRequestDto
import com.pynapli.shared.network.dto.HiddenProfileSummaryDto
import com.pynapli.shared.network.dto.HiddenProfilesListDto
import com.pynapli.shared.network.dto.ReportDto
import com.pynapli.shared.network.dto.ReportHistoryListDto
import com.pynapli.shared.network.dto.SupportContactDto
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType

/** Ktor implementation of [SafetyApi], composed into `KtorPynapliApiClient` via delegation. */
internal class KtorSafetyApi(
    private val support: KtorApiSupport,
) : SafetyApi {
    override suspend fun blockUser(
        targetUserId: String,
        request: BlockUserRequestDto,
    ): AppResult<BlockSummaryDto> =
        support.authenticated { token ->
            support.httpClient
                .post(support.endpoint("/v1/blocks/$targetUserId")) {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }.toAppResult(support.json)
        }

    override suspend fun unblockUser(targetUserId: String): AppResult<Unit> =
        support.authenticated { token ->
            val response =
                support.httpClient.delete(support.endpoint("/v1/blocks/$targetUserId")) {
                    bearerAuth(token)
                }
            if (response.status.value in 200..299) {
                AppResult.Success(Unit)
            } else {
                response.toAppResult<Unit>(support.json)
            }
        }

    override suspend fun listBlocks(): AppResult<BlocksListDto> =
        support.authenticated { token ->
            support.httpClient
                .get(support.endpoint("/v1/blocks")) {
                    bearerAuth(token)
                }.toAppResult(support.json)
        }

    override suspend fun hideProfile(targetUserId: String): AppResult<HiddenProfileSummaryDto> =
        support.authenticated { token ->
            support.httpClient
                .post(support.endpoint("/v1/hidden-profiles/$targetUserId")) {
                    bearerAuth(token)
                }.toAppResult(support.json)
        }

    override suspend fun unhideProfile(targetUserId: String): AppResult<Unit> =
        support.authenticated { token ->
            val response =
                support.httpClient.delete(support.endpoint("/v1/hidden-profiles/$targetUserId")) {
                    bearerAuth(token)
                }
            if (response.status.value in 200..299) {
                AppResult.Success(Unit)
            } else {
                response.toAppResult<Unit>(support.json)
            }
        }

    override suspend fun listHiddenProfiles(): AppResult<HiddenProfilesListDto> =
        support.authenticated { token ->
            support.httpClient
                .get(support.endpoint("/v1/hidden-profiles")) {
                    bearerAuth(token)
                }.toAppResult(support.json)
        }

    override suspend fun submitReport(request: CreateReportRequestDto): AppResult<ReportDto> =
        support.authenticated { token ->
            support.httpClient
                .post(support.endpoint("/v1/reports")) {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }.toAppResult(support.json)
        }

    override suspend fun listReports(limit: Int?): AppResult<ReportHistoryListDto> =
        support.authenticated { token ->
            support.httpClient
                .get(support.endpoint("/v1/reports")) {
                    bearerAuth(token)
                    if (limit != null) parameter("limit", limit)
                }.toAppResult(support.json)
        }

    override suspend fun getSupportContact(): AppResult<SupportContactDto> =
        support.authenticated { token ->
            support.httpClient
                .get(support.endpoint("/v1/support/contact")) {
                    bearerAuth(token)
                }.toAppResult(support.json)
        }
}
