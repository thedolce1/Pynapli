package com.pynapli.shared.auth

import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.PynapliApiClient
import com.pynapli.shared.network.dto.AuthSessionDto
import com.pynapli.shared.network.dto.EmailLoginRequestDto
import com.pynapli.shared.network.dto.EmailSignupRequestDto

class AuthRepository(
    private val apiClient: PynapliApiClient,
    private val sessionStore: SessionStore
) {
    suspend fun currentSession(): StoredSession? = sessionStore.currentSession()

    suspend fun loginEmail(
        email: String,
        password: String,
        platform: String,
        appVersion: String
    ): AppResult<StoredSession> {
        val result = apiClient.emailLogin(
            EmailLoginRequestDto(
                email = email,
                password = password,
                platform = platform,
                appVersion = appVersion
            )
        )
        return result.persistSession()
    }

    suspend fun signupEmail(
        email: String,
        password: String,
        platform: String,
        appVersion: String
    ): AppResult<StoredSession> {
        val result = apiClient.emailSignup(
            EmailSignupRequestDto(
                email = email,
                password = password,
                platform = platform,
                appVersion = appVersion
            )
        )
        return result.persistSession()
    }

    suspend fun logout() {
        apiClient.logout()
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

    private fun AuthSessionDto.toStoredSession(): StoredSession = StoredSession(
        accessToken = accessToken,
        refreshToken = refreshToken,
        userId = user.userId,
        userStatus = user.status
    )
}
