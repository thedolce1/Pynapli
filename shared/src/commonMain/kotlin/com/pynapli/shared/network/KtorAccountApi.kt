package com.pynapli.shared.network

import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.dto.AccountDataExportDto
import com.pynapli.shared.network.dto.DeleteAccountRequestDto
import com.pynapli.shared.network.dto.DeleteAccountResponseDto
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.http.HttpMethod

/** Ktor implementation of [AccountApi], composed into `KtorPynapliApiClient` via delegation. */
internal class KtorAccountApi(
    private val support: KtorApiSupport,
) : AccountApi {
    override suspend fun getLatestDataExportRequest(): AppResult<AccountDataExportDto?> =
        support.authenticated { token ->
            val response =
                support.httpClient.get(support.endpoint("/v1/account/data-export")) {
                    bearerAuth(token)
                }
            if (response.status.value == 204) {
                AppResult.Success(null)
            } else {
                @Suppress("UNCHECKED_CAST")
                response.toAppResult<AccountDataExportDto>(support.json) as AppResult<AccountDataExportDto?>
            }
        }

    override suspend fun requestDataExport(): AppResult<AccountDataExportDto> = support.authPost("/v1/account/data-export")

    override suspend fun deleteAccount(request: DeleteAccountRequestDto): AppResult<DeleteAccountResponseDto> = support.authSend(HttpMethod.Post, "/v1/account/delete", request)
}
