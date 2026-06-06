package com.pynapli.shared.network

import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.dto.AccountDataExportDto
import com.pynapli.shared.network.dto.DeleteAccountRequestDto
import com.pynapli.shared.network.dto.DeleteAccountResponseDto
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType

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

    override suspend fun requestDataExport(): AppResult<AccountDataExportDto> =
        support.authenticated { token ->
            support.httpClient
                .post(support.endpoint("/v1/account/data-export")) {
                    bearerAuth(token)
                }.toAppResult(support.json)
        }

    override suspend fun deleteAccount(request: DeleteAccountRequestDto): AppResult<DeleteAccountResponseDto> =
        support.authenticated { token ->
            support.httpClient
                .post(support.endpoint("/v1/account/delete")) {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }.toAppResult(support.json)
        }
}
