package com.pynapli.shared.network

import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.dto.AccountAppealRequestDto
import com.pynapli.shared.network.dto.AppealDto
import com.pynapli.shared.network.dto.AuthSessionDto
import com.pynapli.shared.network.dto.EmailLoginRequestDto
import com.pynapli.shared.network.dto.EmailSignupCodeResentDto
import com.pynapli.shared.network.dto.EmailSignupRequestDto
import com.pynapli.shared.network.dto.EmailSignupResendRequestDto
import com.pynapli.shared.network.dto.EmailSignupStartedDto
import com.pynapli.shared.network.dto.EmailSignupVerifyRequestDto
import com.pynapli.shared.network.dto.LogoutResponseDto
import com.pynapli.shared.network.dto.PasswordChangeDto
import com.pynapli.shared.network.dto.PasswordChangedDto
import com.pynapli.shared.network.dto.PasswordResetDto
import com.pynapli.shared.network.dto.PasswordResetRequestDto
import com.pynapli.shared.network.dto.PasswordResetRequestedDto
import com.pynapli.shared.network.dto.RefreshSessionRequestDto
import com.pynapli.shared.network.dto.SignedInDevicesDto
import com.pynapli.shared.network.dto.SignedOutDevicesDto
import io.ktor.http.HttpMethod

/** Ktor implementation of [AuthApi], composed into `KtorPynapliApiClient` via interface delegation. */
internal class KtorAuthApi(
    private val support: KtorApiSupport,
) : AuthApi {
    override suspend fun emailSignup(request: EmailSignupRequestDto): AppResult<EmailSignupStartedDto> = support.postJson("/v1/auth/email/signup", request)

    override suspend fun verifyEmailSignup(request: EmailSignupVerifyRequestDto): AppResult<AuthSessionDto> = support.postJson("/v1/auth/email/signup/verify", request)

    override suspend fun resendEmailSignupCode(request: EmailSignupResendRequestDto): AppResult<EmailSignupCodeResentDto> = support.postJson("/v1/auth/email/signup/resend", request)

    override suspend fun emailLogin(request: EmailLoginRequestDto): AppResult<AuthSessionDto> = support.postJson("/v1/auth/email/login", request)

    override suspend fun refreshSession(request: RefreshSessionRequestDto): AppResult<AuthSessionDto> = support.postJson("/v1/auth/refresh", request)

    override suspend fun logout(): AppResult<LogoutResponseDto> = support.authPost("/v1/auth/logout")

    override suspend fun requestPasswordReset(request: PasswordResetRequestDto): AppResult<PasswordResetRequestedDto> = support.postJson("/v1/auth/password/reset-request", request)

    override suspend fun resetPassword(request: PasswordResetDto): AppResult<PasswordChangedDto> = support.postJson("/v1/auth/password/reset", request)

    override suspend fun changePassword(request: PasswordChangeDto): AppResult<PasswordChangedDto> = support.authSend(HttpMethod.Post, "/v1/auth/password/change", request)

    override suspend fun listSignedInDevices(): AppResult<SignedInDevicesDto> = support.authGet("/v1/auth/sessions")

    override suspend fun signOutDevice(sessionId: String): AppResult<SignedOutDevicesDto> = support.authDelete("/v1/auth/sessions/$sessionId")

    override suspend fun signOutOtherDevices(): AppResult<SignedOutDevicesDto> = support.authPost("/v1/auth/sessions/sign-out-others")

    override suspend fun submitAccountAppeal(request: AccountAppealRequestDto): AppResult<AppealDto> = support.postJson("/v1/auth/account-appeal", request)
}
