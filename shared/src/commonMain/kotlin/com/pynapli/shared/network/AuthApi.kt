package com.pynapli.shared.network

import com.pynapli.shared.core.result.AppError
import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.dto.AccountAppealRequestDto
import com.pynapli.shared.network.dto.AppealDto
import com.pynapli.shared.network.dto.AuthSessionDto
import com.pynapli.shared.network.dto.EmailLoginRequestDto
import com.pynapli.shared.network.dto.EmailSignupRequestDto
import com.pynapli.shared.network.dto.LogoutResponseDto
import com.pynapli.shared.network.dto.PasswordChangeDto
import com.pynapli.shared.network.dto.PasswordChangedDto
import com.pynapli.shared.network.dto.PasswordResetDto
import com.pynapli.shared.network.dto.PasswordResetRequestDto
import com.pynapli.shared.network.dto.PasswordResetRequestedDto
import com.pynapli.shared.network.dto.RefreshSessionRequestDto
import com.pynapli.shared.network.dto.SignedInDevicesDto
import com.pynapli.shared.network.dto.SignedOutDevicesDto

/**
 * Authentication slice of the API surface, split out of the monolithic [PynapliApiClient]
 * (which now composes the per-feature interfaces). Signatures are unchanged.
 */
interface AuthApi {
    suspend fun emailSignup(request: EmailSignupRequestDto): AppResult<AuthSessionDto>

    suspend fun emailLogin(request: EmailLoginRequestDto): AppResult<AuthSessionDto>

    suspend fun refreshSession(request: RefreshSessionRequestDto): AppResult<AuthSessionDto>

    suspend fun logout(): AppResult<LogoutResponseDto>

    // Defaults keep test doubles compiling; the Ktor client implements all three.
    suspend fun requestPasswordReset(request: PasswordResetRequestDto): AppResult<PasswordResetRequestedDto> = notSupported()

    suspend fun resetPassword(request: PasswordResetDto): AppResult<PasswordChangedDto> = notSupported()

    suspend fun changePassword(request: PasswordChangeDto): AppResult<PasswordChangedDto> = notSupported()

    suspend fun listSignedInDevices(): AppResult<SignedInDevicesDto> = notSupported()

    suspend fun signOutDevice(sessionId: String): AppResult<SignedOutDevicesDto> = notSupported()

    suspend fun signOutOtherDevices(): AppResult<SignedOutDevicesDto> = notSupported()

    /** Appeal from a suspended or banned account, which can't sign in. */
    suspend fun submitAccountAppeal(request: AccountAppealRequestDto): AppResult<AppealDto> = notSupported()
}

private fun notSupported(): AppResult.Failure = AppResult.Failure(AppError(code = "NOT_SUPPORTED", message = "Not available."))
