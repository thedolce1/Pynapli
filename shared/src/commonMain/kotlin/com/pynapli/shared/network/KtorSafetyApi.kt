package com.pynapli.shared.network

import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.dto.AppealDto
import com.pynapli.shared.network.dto.AppealListDto
import com.pynapli.shared.network.dto.BlockSummaryDto
import com.pynapli.shared.network.dto.BlockUserRequestDto
import com.pynapli.shared.network.dto.BlocksListDto
import com.pynapli.shared.network.dto.CreateAppealRequestDto
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
import io.ktor.http.HttpMethod

/** Ktor implementation of [SafetyApi], composed into `KtorPynapliApiClient` via delegation. */
internal class KtorSafetyApi(
    private val support: KtorApiSupport,
) : SafetyApi {
    override suspend fun blockUser(
        targetUserId: String,
        request: BlockUserRequestDto,
    ): AppResult<BlockSummaryDto> = support.authSend(HttpMethod.Post, "/v1/blocks/$targetUserId", request)

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

    override suspend fun listBlocks(): AppResult<BlocksListDto> = support.authGet("/v1/blocks")

    override suspend fun hideProfile(targetUserId: String): AppResult<HiddenProfileSummaryDto> = support.authPost("/v1/hidden-profiles/$targetUserId")

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

    override suspend fun listHiddenProfiles(): AppResult<HiddenProfilesListDto> = support.authGet("/v1/hidden-profiles")

    override suspend fun submitReport(request: CreateReportRequestDto): AppResult<ReportDto> = support.authSend(HttpMethod.Post, "/v1/reports", request)

    override suspend fun listReports(limit: Int?): AppResult<ReportHistoryListDto> =
        support.authGet("/v1/reports") {
            if (limit != null) parameter("limit", limit)
        }

    override suspend fun getSupportContact(): AppResult<SupportContactDto> = support.authGet("/v1/support/contact")

    override suspend fun listAppeals(): AppResult<AppealListDto> = support.authGet("/v1/appeals")

    override suspend fun submitAppeal(request: CreateAppealRequestDto): AppResult<AppealDto> = support.authSend(HttpMethod.Post, "/v1/appeals", request)

    override suspend fun withdrawAppeal(appealId: String): AppResult<AppealDto> = support.authPost("/v1/appeals/$appealId/withdraw")
}
