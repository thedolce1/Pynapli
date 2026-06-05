package com.pynapli.shared.network

import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.dto.AuthSessionDto
import com.pynapli.shared.network.dto.EmailLoginRequestDto
import com.pynapli.shared.network.dto.EmailSignupRequestDto
import com.pynapli.shared.network.dto.LogoutResponseDto
import com.pynapli.shared.network.dto.RefreshSessionRequestDto
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post

/** Ktor implementation of [AuthApi], composed into `KtorPynapliApiClient` via interface delegation. */
internal class KtorAuthApi(
    private val support: KtorApiSupport,
) : AuthApi {
    override suspend fun emailSignup(request: EmailSignupRequestDto): AppResult<AuthSessionDto> = support.postJson("/v1/auth/email/signup", request)

    override suspend fun emailLogin(request: EmailLoginRequestDto): AppResult<AuthSessionDto> = support.postJson("/v1/auth/email/login", request)

    override suspend fun refreshSession(request: RefreshSessionRequestDto): AppResult<AuthSessionDto> = support.postJson("/v1/auth/refresh", request)

    override suspend fun logout(): AppResult<LogoutResponseDto> =
        support.authenticated { token ->
            support.httpClient
                .post(support.endpoint("/v1/auth/logout")) {
                    bearerAuth(token)
                }.toAppResult(support.json)
        }
}
