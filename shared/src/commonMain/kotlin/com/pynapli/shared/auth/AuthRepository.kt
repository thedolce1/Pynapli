package com.pynapli.shared.auth

import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.AuthApi
import com.pynapli.shared.network.ProfileApi
import com.pynapli.shared.network.PynapliApiClient
import com.pynapli.shared.network.dto.AuthSessionDto
import com.pynapli.shared.network.dto.EmailLoginRequestDto
import com.pynapli.shared.network.dto.EmailSignupCodeResentDto
import com.pynapli.shared.network.dto.EmailSignupRequestDto
import com.pynapli.shared.network.dto.EmailSignupResendRequestDto
import com.pynapli.shared.network.dto.EmailSignupStartedDto
import com.pynapli.shared.network.dto.EmailSignupVerifyRequestDto
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
                    // A store shared across processes (web tabs) may have been rotated by another
                    // tab meanwhile; only a rejection of the session still stored means it is dead.
                    val latest = sessionStore.currentSession()
                    if (latest != null && latest.refreshToken != session.refreshToken) return latest
                    // A 401 has already been through the refresh-on-401 path, and StoredSessionRefresher
                    // clears the store itself when the server rejects the refresh token. A session still
                    // stored unchanged means that refresh failed transiently (its response was lost, say);
                    // keep it so the next request retries, which the server answers idempotently.
                    // (A blank stored access token never reaches the server or the refresher, so it
                    // gets no such benefit of the doubt.)
                    if (latest != null && latest.accessToken.isNotBlank() && result.error.code in unauthorizedCodes) return latest
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

    /**
     * Starts email sign-up. No session yet: the server emails a code (or, for an address that already
     * has an account, a notice) and answers the same either way. Keep the returned signup token for
     * [verifyEmailSignup] and [resendEmailSignupCode]; it never leaves the device otherwise.
     */
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
    ): AppResult<EmailSignupStartedDto> =
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

    /** Finishes sign-up with the emailed code: the account is created now and its session stored. */
    suspend fun verifyEmailSignup(
        email: String,
        code: String,
        signupToken: String,
        platform: String,
        appVersion: String,
        deviceId: String? = null,
    ): AppResult<StoredSession> =
        authApi
            .verifyEmailSignup(
                EmailSignupVerifyRequestDto(
                    email = email,
                    code = code,
                    signupToken = signupToken,
                    deviceId = deviceId,
                    platform = platform,
                    appVersion = appVersion,
                ),
            ).persistSession()

    suspend fun resendEmailSignupCode(
        email: String,
        signupToken: String,
        deviceId: String? = null,
    ): AppResult<EmailSignupCodeResentDto> = authApi.resendEmailSignupCode(EmailSignupResendRequestDto(email = email, signupToken = signupToken, deviceId = deviceId))

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
