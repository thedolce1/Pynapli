package com.pynapli.shared.network

import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.dto.AgeVerificationRequirementsDto
import com.pynapli.shared.network.dto.AgeVerificationStatusDto
import com.pynapli.shared.network.dto.StartAgeVerificationRequestDto
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType

/** Ktor implementation of [VerificationApi], composed into `KtorPynapliApiClient` via delegation. */
internal class KtorVerificationApi(
    private val support: KtorApiSupport,
) : VerificationApi {
    override suspend fun getAgeVerificationRequirements(): AppResult<AgeVerificationRequirementsDto> =
        support.authenticated { token ->
            support.httpClient
                .get(support.endpoint("/v1/verification/requirements")) {
                    bearerAuth(token)
                }.toAppResult(support.json)
        }

    override suspend fun getAgeVerificationStatus(): AppResult<AgeVerificationStatusDto> =
        support.authenticated { token ->
            support.httpClient
                .get(support.endpoint("/v1/verification/status")) {
                    bearerAuth(token)
                }.toAppResult(support.json)
        }

    override suspend fun startAgeVerification(
        request: StartAgeVerificationRequestDto,
    ): AppResult<AgeVerificationStatusDto> =
        support.authenticated { token ->
            support.httpClient
                .post(support.endpoint("/v1/verification/start")) {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }.toAppResult(support.json)
        }
}
