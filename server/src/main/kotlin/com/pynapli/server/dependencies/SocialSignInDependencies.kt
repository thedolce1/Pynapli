package com.pynapli.server.dependencies

import com.pynapli.server.auth.AuthService
import com.pynapli.server.auth.BoundedWorkLauncher
import com.pynapli.server.auth.social.AppleGrantRevoker
import com.pynapli.server.auth.social.AppleTokenClient
import com.pynapli.server.auth.social.HttpAppleTokenClient
import com.pynapli.server.auth.social.JdbcSocialIdentityRepository
import com.pynapli.server.auth.social.RedisSocialNonceStore
import com.pynapli.server.auth.social.SocialAuthService
import com.pynapli.server.auth.social.SocialIdentityVerifier
import com.pynapli.server.auth.social.SocialProvider
import com.pynapli.server.auth.social.SocialTokenSeal
import com.pynapli.server.config.ServerConfig
import com.pynapli.server.config.appleSignInConfigured
import com.pynapli.server.config.googleSignInConfigured
import com.pynapli.server.safety.RateLimitService
import kotlinx.coroutines.CoroutineScope
import java.util.UUID

/**
 * Apple's token client whenever its key is configured (whatever the sign-in flag says), so stored
 * grants can always be revoked on deletion. Null without the key.
 */
internal fun buildAppleTokenClient(config: ServerConfig): AppleTokenClient? {
    val teamId = config.appleTeamId?.takeIf { it.isNotBlank() } ?: return null
    val keyId = config.appleKeyId?.takeIf { it.isNotBlank() } ?: return null
    val pem = config.applePrivateKeyPem?.takeIf { it.isNotBlank() } ?: return null
    return HttpAppleTokenClient(
        teamId = teamId,
        keyId = keyId,
        privateKeyPem = pem,
        redirectUris = config.appleRedirectUris,
        tokenUrl = config.appleTokenUrl ?: HttpAppleTokenClient.APPLE_TOKEN_URL,
        revokeUrl = config.appleRevokeUrl ?: HttpAppleTokenClient.APPLE_REVOKE_URL,
    )
}

/** The providers that are on and fully configured, each with its verifier. */
internal fun buildSocialVerifiers(config: ServerConfig): Map<SocialProvider, SocialIdentityVerifier> =
    buildMap {
        if (config.googleSignInConfigured()) {
            put(
                SocialProvider.GOOGLE,
                SocialIdentityVerifier(
                    provider = SocialProvider.GOOGLE,
                    acceptedAudiences = config.googleClientIds,
                    jwksUrl = config.googleJwksUrl ?: SocialIdentityVerifier.GOOGLE_JWKS_URL,
                ),
            )
        }
        if (config.appleSignInConfigured()) {
            put(
                SocialProvider.APPLE,
                SocialIdentityVerifier(
                    provider = SocialProvider.APPLE,
                    acceptedAudiences = config.appleClientIds,
                    jwksUrl = config.appleJwksUrl ?: SocialIdentityVerifier.APPLE_JWKS_URL,
                ),
            )
        }
    }

internal fun buildSocialAuthService(
    config: ServerConfig,
    database: Database,
    redisCache: RedisCache,
    authService: AuthService,
    rateLimitService: RateLimitService,
    backgroundScope: CoroutineScope,
    tokenSeal: SocialTokenSeal,
    appleTokenClient: AppleTokenClient?,
    appleGrantRevoker: AppleGrantRevoker?,
    onAccountVerified: suspend (UUID, String) -> Unit,
    onSignInWithAccountAddress: suspend (UUID, String) -> Unit,
): SocialAuthService =
    SocialAuthService(
        verifiers = buildSocialVerifiers(config),
        nonceStore = RedisSocialNonceStore(redisCache),
        repository = JdbcSocialIdentityRepository(database.dataSource),
        authService = authService,
        tokenSeal = tokenSeal,
        appleTokenClient = appleTokenClient,
        appleGrantRevoker = appleGrantRevoker,
        rateLimitService = rateLimitService,
        launchBackground = BoundedWorkLauncher(backgroundScope, label = "apple-code-exchange")::launch,
        onAccountVerified = onAccountVerified,
        onSignInWithAccountAddress = onSignInWithAccountAddress,
    )
