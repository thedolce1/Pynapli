package com.pynapli.shared.network

import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.dto.AuthSessionDto
import com.pynapli.shared.network.dto.EmailLoginRequestDto
import com.pynapli.shared.network.dto.EmailSignupRequestDto
import com.pynapli.shared.network.dto.LogoutResponseDto
import com.pynapli.shared.network.dto.RefreshSessionRequestDto

/**
 * Authentication slice of the API surface, split out of the monolithic [PynapliApiClient]
 * (which now composes the per-feature interfaces). Signatures are unchanged.
 */
interface AuthApi {
    suspend fun emailSignup(request: EmailSignupRequestDto): AppResult<AuthSessionDto>

    suspend fun emailLogin(request: EmailLoginRequestDto): AppResult<AuthSessionDto>

    suspend fun refreshSession(request: RefreshSessionRequestDto): AppResult<AuthSessionDto>

    suspend fun logout(): AppResult<LogoutResponseDto>
}
