package com.pynapli.shared.auth

import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.AuthApi
import com.pynapli.shared.network.ProfileApi
import com.pynapli.shared.network.PynapliApiClient
import com.pynapli.shared.network.dto.AuthSessionDto
import com.pynapli.shared.network.dto.EmailLoginRequestDto
import com.pynapli.shared.network.dto.EmailSignupRequestDto

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
    ): AppResult<StoredSession> {
        val result =
            authApi.emailLogin(
                EmailLoginRequestDto(
                    email = email,
                    password = password,
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
                    platform = platform,
                    appVersion = appVersion,
                ),
            )
        return result.persistSession()
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

    private companion object {
        private val terminalAuthErrorCodes =
            setOf(
                "UNAUTHENTICATED",
                "INVALID_REFRESH_TOKEN",
                "ACCOUNT_NOT_ACTIVE",
                "HTTP_401",
            )
    }
}
