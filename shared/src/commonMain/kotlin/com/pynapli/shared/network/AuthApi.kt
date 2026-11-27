package com.pynapli.shared.network

import com.pynapli.shared.core.result.AppError
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
import com.pynapli.shared.network.dto.LinkSocialIdentityRequestDto
import com.pynapli.shared.network.dto.LinkedIdentitiesDto
import com.pynapli.shared.network.dto.LogoutResponseDto
import com.pynapli.shared.network.dto.PasswordChangeDto
import com.pynapli.shared.network.dto.PasswordChangedDto
import com.pynapli.shared.network.dto.PasswordResetDto
import com.pynapli.shared.network.dto.PasswordResetRequestDto
import com.pynapli.shared.network.dto.PasswordResetRequestedDto
import com.pynapli.shared.network.dto.RefreshSessionRequestDto
import com.pynapli.shared.network.dto.SignedInDevicesDto
import com.pynapli.shared.network.dto.SignedOutDevicesDto
import com.pynapli.shared.network.dto.SocialAccountAppealRequestDto
import com.pynapli.shared.network.dto.SocialNonceDto
import com.pynapli.shared.network.dto.SocialSignInConfigDto
import com.pynapli.shared.network.dto.SocialSignInRequestDto
import com.pynapli.shared.network.dto.UnlinkedIdentityDto

/**
 * Authentication slice of the API surface, split out of the monolithic [PynapliApiClient]
 * (which now composes the per-feature interfaces). Signatures are unchanged.
 */
interface AuthApi {
    /**
     * Starts email sign-up (ONBOARDING_AUTH_SPEC, "Membership privacy"): no session, the same answer
     * whether or not the address has an account. The session comes from [verifyEmailSignup].
     */
    suspend fun emailSignup(request: EmailSignupRequestDto): AppResult<EmailSignupStartedDto>

    suspend fun verifyEmailSignup(request: EmailSignupVerifyRequestDto): AppResult<AuthSessionDto> = notSupported()

    suspend fun resendEmailSignupCode(request: EmailSignupResendRequestDto): AppResult<EmailSignupCodeResentDto> = notSupported()

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

    // Continue with Google / Apple (ONBOARDING_AUTH_SPEC). Defaults keep test doubles compiling, and
    // a "not supported" config simply hides the buttons.

    /** Which providers the server has switched on. Unauthenticated. */
    suspend fun socialSignInConfig(): AppResult<SocialSignInConfigDto> = notSupported()

    /** A fresh single-use nonce for one provider attempt. Unauthenticated; the body is ignored. */
    suspend fun socialNonce(deviceId: String? = null): AppResult<SocialNonceDto> = notSupported()

    /** Signs in (or, with the age-gate fields, signs up) with a provider ID token. Same session as email login. */
    suspend fun socialSignIn(
        providerWireName: String,
        request: SocialSignInRequestDto,
    ): AppResult<AuthSessionDto> = notSupported()

    /** Appeal from a suspended account that signs in with a provider: a fresh provider sign-in, no session. */
    suspend fun submitSocialAccountAppeal(
        providerWireName: String,
        request: SocialAccountAppealRequestDto,
    ): AppResult<AppealDto> = notSupported()

    /** Signed in: the account's sign-in methods (`email`, `google`, `apple`) and its password flag. */
    suspend fun listSignInMethods(): AppResult<LinkedIdentitiesDto> = notSupported()

    /** Answers the account's updated methods. An invalid provider token is 400 here, never 401. */
    suspend fun linkSocialIdentity(
        providerWireName: String,
        request: LinkSocialIdentityRequestDto,
    ): AppResult<LinkedIdentitiesDto> = notSupported()

    suspend fun unlinkSocialIdentity(providerWireName: String): AppResult<UnlinkedIdentityDto> = notSupported()
}

private fun notSupported(): AppResult.Failure = AppResult.Failure(AppError(code = "NOT_SUPPORTED", message = "Not available."))
