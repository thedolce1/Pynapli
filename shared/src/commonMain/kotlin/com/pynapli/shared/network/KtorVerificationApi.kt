package com.pynapli.shared.network

import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.dto.AgeVerificationRequirementsDto
import com.pynapli.shared.network.dto.AgeVerificationStatusDto
import com.pynapli.shared.network.dto.StartAgeVerificationRequestDto
import io.ktor.client.request.get
import io.ktor.http.HttpMethod

/** Ktor implementation of [VerificationApi], composed into `KtorPynapliApiClient` via delegation. */
internal class KtorVerificationApi(
    private val support: KtorApiSupport,
) : VerificationApi {
    override suspend fun getAgeVerificationRequirements(): AppResult<AgeVerificationRequirementsDto> = support.authGet("/v1/verification/requirements")

    override suspend fun getAgeVerificationStatus(): AppResult<AgeVerificationStatusDto> = support.authGet("/v1/verification/status")

    override suspend fun startAgeVerification(
        request: StartAgeVerificationRequestDto,
    ): AppResult<AgeVerificationStatusDto> = support.authSend(HttpMethod.Post, "/v1/verification/start", request)
}
