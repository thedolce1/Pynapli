package com.pynapli.shared.auth

import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.AuthApi
import com.pynapli.shared.network.dto.LinkSocialIdentityRequestDto
import com.pynapli.shared.network.dto.LinkedIdentitiesDto
import com.pynapli.shared.network.dto.SocialNonceDto
import com.pynapli.shared.network.dto.SocialSignInConfigDto
import com.pynapli.shared.network.dto.UnlinkedIdentityDto

/**
 * Settings > Sign-in methods (ONBOARDING_AUTH_SPEC): which methods the signed-in account can sign
 * in with, and adding or removing a provider. Separate from [AuthRepository] because nothing here
 * touches the stored session: linking never changes who is signed in.
 *
 * There is no automatic linking by email anywhere (a takeover risk, and it would reveal who is a
 * member): a provider is only ever added here, by a member who is already signed in.
 */
class SocialIdentityRepository(
    private val authApi: AuthApi,
) {
    suspend fun config(): AppResult<SocialSignInConfigDto> = authApi.socialSignInConfig()

    suspend fun nonce(deviceId: String? = null): AppResult<SocialNonceDto> = authApi.socialNonce(deviceId)

    suspend fun signInMethods(): AppResult<LinkedIdentitiesDto> = authApi.listSignInMethods()

    /** Answers the account's updated methods (also when it was already linked to this account). */
    suspend fun link(
        provider: SocialProvider,
        idToken: String,
        nonce: String,
        authorizationCode: String?,
    ): AppResult<LinkedIdentitiesDto> =
        authApi.linkSocialIdentity(
            providerWireName = provider.wireName,
            request = LinkSocialIdentityRequestDto(idToken = idToken, nonce = nonce, authorizationCode = authorizationCode),
        )

    suspend fun unlink(provider: SocialProvider): AppResult<UnlinkedIdentityDto> = authApi.unlinkSocialIdentity(provider.wireName)
}
