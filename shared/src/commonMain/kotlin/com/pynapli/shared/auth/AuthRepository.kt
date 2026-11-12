package com.pynapli.shared.auth

import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.AuthApi
import com.pynapli.shared.network.ProfileApi
import com.pynapli.shared.network.PynapliApiClient
import com.pynapli.shared.network.dto.AuthSessionDto
import com.pynapli.shared.network.dto.EmailLoginRequestDto
import com.pynapli.shared.network.dto.EmailSignupRequestDto
import com.pynapli.shared.network.dto.PasswordResetDto
import com.pynapli.shared.network.dto.PasswordResetRequestDto
import com.pynapli.shared.network.dto.PasswordResetRequestedDto

class AuthRepository(
    private val authApi: AuthApi,
    private val profileApi: ProfileApi,
    private val sessionStore: SessionStore,
) {
    // Convenience for production call sites that hold the aggregate client; the
    // primary constructor takes only the segments this repository actually uses.
    constructor(apiClient: PynapliApiClient, sessionStore: SessionStore) : this(
        authApi = apiClient,
        profileApi = apiClient,
        sessionStore = sessionStore,
    )

    suspend fun currentSession(): StoredSession? = sessionStore.currentSession()

    suspend fun currentSessionValidated(): StoredSession? {
        val session = sessionStore.currentSession() ?: return null
        return when (val result = profileApi.getMe()) {
            is AppResult.Success -> session
            is AppResult.Failure -> {
                if (result.error.code in terminalAuthErrorCodes) {
                    sessionStore.clear()
                    null
                } else {
                    session
                }
            }
        }
    }

    suspend fun loginEmail(
        email: String,
        password: String,
        platform: String,
        appVersion: String,
        deviceId: String? = null,
    ): AppResult<StoredSession> {
        val result =
            authApi.emailLogin(
                EmailLoginRequestDto(
                    email = email,
                    password = password,
                    deviceId = deviceId,
                    platform = platform,
                    appVersion = appVersion,
                ),
            )
        return result.persistSession()
    }

    suspend fun signupEmail(
        email: String,
        password: String,
        birthdate: String,
        confirmedAdult: Boolean,
        acceptedTerms: Boolean,
        acceptedPrivacy: Boolean,
        platform: String,
        appVersion: String,
        deviceId: String? = null,
    ): AppResult<StoredSession> {
        val result =
            authApi.emailSignup(
                EmailSignupRequestDto(
                    email = email,
                    password = password,
                    birthdate = birthdate,
                    confirmedAdult = confirmedAdult,
                    acceptedTerms = acceptedTerms,
                    acceptedPrivacy = acceptedPrivacy,
                    deviceId = deviceId,
                    platform = platform,
                    appVersion = appVersion,
                ),
            )
        return result.persistSession()
    }

    suspend fun submitAccountAppeal(
        email: String,
        password: String,
        reason: String,
        deviceId: String? = null,
    ): AppResult<com.pynapli.shared.network.dto.AppealDto> =
        authApi.submitAccountAppeal(
            com.pynapli.shared.network.dto
                .AccountAppealRequestDto(email = email, password = password, reason = reason, deviceId = deviceId),
        )

    suspend fun requestPasswordReset(
        email: String,
        deviceId: String? = null,
    ): AppResult<PasswordResetRequestedDto> = authApi.requestPasswordReset(PasswordResetRequestDto(email = email, deviceId = deviceId))

    /** Sets the new password with the emailed code, then signs in with it (the reset signed out every device). */
    suspend fun resetPasswordAndSignIn(
        email: String,
        code: String,
        newPassword: String,
        platform: String,
        appVersion: String,
        deviceId: String? = null,
    ): AppResult<StoredSession> =
        when (val reset = authApi.resetPassword(PasswordResetDto(email = email, code = code, newPassword = newPassword))) {
            is AppResult.Success -> loginEmail(email, newPassword, platform, appVersion, deviceId)
            is AppResult.Failure -> reset
        }

    suspend fun logout() {
        authApi.logout()
        sessionStore.clear()
    }

    private suspend fun AppResult<AuthSessionDto>.persistSession(): AppResult<StoredSession> =
        when (this) {
            is AppResult.Success -> {
                sessionStore.save(value)
                AppResult.Success(sessionStore.currentSession() ?: value.toStoredSession())
            }

            is AppResult.Failure -> this
        }

    private fun AuthSessionDto.toStoredSession(): StoredSession =
        StoredSession(
            accessToken = accessToken,
            refreshToken = refreshToken,
            userId = user.userId,
            userStatus = user.status,
        )
}
