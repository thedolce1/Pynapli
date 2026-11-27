package com.pynapli.app.auth

import com.pynapli.shared.auth.SocialProvider

/**
 * The platform seam behind "Continue with Google / Apple" (ONBOARDING_AUTH_SPEC). Each shell's graph
 * supplies one: Credential Manager on Android (Google only), Google Identity Services and Sign in
 * with Apple JS on the web, and [UnavailableSocialSignInProvider] on desktop and (until its Xcode
 * project lands) iOS.
 *
 * It only ever asks the provider for an ID token for a nonce it is handed. It decides nothing:
 * the server verifies the token, its audience and the nonce, and owns what happens next. It holds
 * no client secret (there is none on a client) and logs no token.
 */
interface SocialSignInProvider {
    /**
     * True when this platform can run [provider] at all: the platform has an integration for it and
     * the shell was built (or the page configured) with that provider's client id. A button shows
     * only when this and the server's `/v1/auth/social/config` flag are both true.
     */
    fun supports(provider: SocialProvider): Boolean

    /**
     * The server has [providers] switched on: a chance to load what the first tap needs (the web
     * fetches the provider scripts here, and only here, so a page with the feature off never loads
     * third-party code). Never shows anything.
     */
    fun warmUp(providers: Set<SocialProvider>) {}

    /**
     * Runs the provider's own sign-in UI and returns its ID token for [nonce]. [nonce] is already in
     * the form the provider must see ([com.pynapli.shared.auth.SocialProviderNonce]); it is fresh
     * for this attempt and must not be cached or reused.
     */
    suspend fun requestIdToken(
        provider: SocialProvider,
        nonce: String,
    ): SocialCredentialResult

    /** The member signed out: forget any provider-side "signed in" state (best effort, never throws). */
    suspend fun onSignedOut() {}
}

sealed interface SocialCredentialResult {
    /**
     * [authorizationCode] is Apple's single-use code. [givenName] is what Apple sends on the very
     * first authorisation only; it is never sent to the server as a display name (see
     * SocialSignInRequestDto). [toString] never prints the token or code.
     */
    class Success(
        val idToken: String,
        val authorizationCode: String? = null,
        val givenName: String? = null,
    ) : SocialCredentialResult {
        override fun toString(): String = "SocialCredentialResult.Success(idToken=<redacted>)"
    }

    /** The person closed the provider's sheet or popup. Not an error: nothing is shown. */
    data object Cancelled : SocialCredentialResult

    /** This device can't run the provider (no Google account services, script blocked, no client id). */
    data object Unavailable : SocialCredentialResult

    /** Anything else. [message] is safe to show and never contains a token. */
    data class Error(
        val message: String,
    ) : SocialCredentialResult
}

/**
 * Desktop (dev-only) and iOS for now. iOS gets Sign in with Apple through
 * ASAuthorizationController once the Xcode project exists (plan D1) and Google through its iOS SDK;
 * both arrive as an iosApp-side implementation of this interface, not here.
 */
object UnavailableSocialSignInProvider : SocialSignInProvider {
    override fun supports(provider: SocialProvider): Boolean = false

    override suspend fun requestIdToken(
        provider: SocialProvider,
        nonce: String,
    ): SocialCredentialResult = SocialCredentialResult.Unavailable
}
