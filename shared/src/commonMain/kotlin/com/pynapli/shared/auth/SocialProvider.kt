package com.pynapli.shared.auth

/**
 * The sign-in providers behind "Continue with Google / Apple" (ONBOARDING_AUTH_SPEC). [wireName] is
 * the path segment and the `provider` value the server uses.
 */
enum class SocialProvider(
    val wireName: String,
    val displayName: String,
) {
    Google(wireName = "google", displayName = "Google"),
    Apple(wireName = "apple", displayName = "Apple"),
    ;

    companion object {
        fun fromWire(value: String): SocialProvider? = entries.firstOrNull { it.wireName.equals(value.trim(), ignoreCase = true) }
    }
}

/** Server error codes of the social sign-in and linking routes (API_CONTRACT_SPEC). */
object SocialAuthErrorCodes {
    /** Unknown identity without birthday + policy fields: run Birthday -> Policies, then try again. */
    const val NEEDS_AGE_GATE = "SOCIAL_SIGNUP_NEEDS_AGE_GATE"

    /** The provider's address already has a Pynapli account. Never linked automatically. */
    const val EMAIL_HAS_ACCOUNT = "SOCIAL_EMAIL_HAS_ACCOUNT"

    const val INVALID_TOKEN = "INVALID_SOCIAL_TOKEN"

    /** The feature is off on the server: hide every button. */
    const val UNAVAILABLE = "SOCIAL_SIGN_IN_UNAVAILABLE"

    /** Provider keys or the nonce store unreachable: try again shortly. */
    const val TEMPORARILY_UNAVAILABLE = "SOCIAL_SIGN_IN_TEMPORARILY_UNAVAILABLE"

    /** A suspended, banned or paused account: the appeal form, through a fresh provider sign-in. */
    const val ACCOUNT_NOT_ACTIVE = "ACCOUNT_NOT_ACTIVE"

    /** Linking: that Google/Apple account already belongs to another Pynapli account. */
    const val IDENTITY_IN_USE = "SOCIAL_IDENTITY_IN_USE"

    /** Linking: this account already has a different Google (or Apple) identity. */
    const val PROVIDER_ALREADY_LINKED = "SOCIAL_PROVIDER_ALREADY_LINKED"

    /** Unlinking: the account would be left with no way to sign in. */
    const val LAST_SIGN_IN_METHOD = "LAST_SIGN_IN_METHOD"

    /** Unlinking: that provider is not linked (already removed elsewhere). */
    const val IDENTITY_NOT_LINKED = "SOCIAL_IDENTITY_NOT_LINKED"

    /** Social appeal: the account is active, nothing to appeal. */
    const val ACCOUNT_NOT_RESTRICTED = "ACCOUNT_NOT_RESTRICTED"

    /** Social appeal: the proven identity has no account. */
    const val ACCOUNT_NOT_FOUND = "SOCIAL_ACCOUNT_NOT_FOUND"
}

/** The `provider` value `GET /v1/auth/identities` uses for the password method. */
const val PASSWORD_IDENTITY_PROVIDER = "email"

/**
 * The one place that decides what nonce string a provider is handed for a server nonce
 * (API_CONTRACT_SPEC, "Section 5 contract"):
 * - Google (Credential Manager `setNonce`, GIS `nonce`): the server nonce as it is.
 * - Apple (Sign in with Apple JS `nonce`, and later iOS `ASAuthorizationAppleIDRequest.nonce`): the
 *   lowercase hex SHA-256 of it, so the token's `nonce` claim is that hash.
 * Pynapli itself always gets the raw nonce in the request body; only the provider sees this form.
 */
object SocialProviderNonce {
    fun forProviderRequest(
        provider: SocialProvider,
        serverNonce: String,
    ): String =
        when (provider) {
            SocialProvider.Google -> serverNonce
            SocialProvider.Apple -> Sha256.hex(serverNonce)
        }
}

/**
 * The sign-up answers a social sign-up sends after `SOCIAL_SIGNUP_NEEDS_AGE_GATE`: the same values
 * the email sign-up sends, gathered by the same Birthday and Policies steps.
 */
data class SocialAgeGate(
    val birthdate: String,
    val confirmedAdult: Boolean,
    val acceptedTerms: Boolean,
    val acceptedPrivacy: Boolean,
)
