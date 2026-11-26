package com.pynapli.shared.network.dto

import kotlinx.serialization.Serializable

// "Continue with Google / Apple" (API_CONTRACT_SPEC, "Section 5 contract"). The server verifies every
// provider ID token itself; nothing here is trusted from the client except as input to that check.

/** `GET /v1/auth/social/config`: each provider is true only when the server flag is on and it is configured. */
@Serializable
data class SocialSignInConfigDto(
    val google: Boolean,
    val apple: Boolean,
)

/**
 * `POST /v1/auth/social/nonce`: a single-use value the provider must echo in the ID token's `nonce`
 * claim. Google: pass it as is. Apple: pass the lowercase hex SHA-256 of it (the raw value is also
 * accepted). Every answer from a social endpoint that verified the token spends it; get a fresh one
 * (and a fresh ID token) for each attempt.
 */
@Serializable
data class SocialNonceDto(
    val nonce: String,
    val expiresInSeconds: Int,
)

/**
 * `POST /v1/auth/social/{google|apple}`. The age-gate and policy fields mirror [EmailSignupRequestDto]
 * exactly; they are only read when the identity has no account yet. Leave [birthdate] null on the first
 * attempt: an unknown identity then answers `422 SOCIAL_SIGNUP_NEEDS_AGE_GATE`. There is no display
 * name field: the provider's real name is never used; the Profile step sets one.
 */
@Serializable
data class SocialSignInRequestDto(
    val idToken: String,
    val nonce: String,
    /** Apple only: the authorization code, exchanged server-side for the refresh token deletion revokes. */
    val authorizationCode: String? = null,
    val birthdate: String? = null,
    val confirmedAdult: Boolean = false,
    val acceptedTerms: Boolean = false,
    val acceptedPrivacy: Boolean = false,
    val deviceId: String? = null,
    val platform: String? = null,
    val appVersion: String? = null,
) {
    // Never print a bearer-grade credential (logs, crash reports): the token and code are redacted.
    // Serialization doesn't use toString, so the wire format is unchanged.
    override fun toString(): String =
        "SocialSignInRequestDto(idToken=$REDACTED, nonce=$REDACTED, authorizationCode=${redactedOrNull(authorizationCode)}, " +
            "birthdate=${if (birthdate == null) "null" else REDACTED}, confirmedAdult=$confirmedAdult, acceptedTerms=$acceptedTerms, " +
            "acceptedPrivacy=$acceptedPrivacy, deviceId=$deviceId, platform=$platform, appVersion=$appVersion)"
}

/** `POST /v1/auth/identities/{google|apple}` (signed in): links that provider identity to this account. */
@Serializable
data class LinkSocialIdentityRequestDto(
    val idToken: String,
    val nonce: String,
    val authorizationCode: String? = null,
) {
    override fun toString(): String = "LinkSocialIdentityRequestDto(idToken=$REDACTED, nonce=$REDACTED, authorizationCode=${redactedOrNull(authorizationCode)})"
}

private const val REDACTED = "<redacted>"

private fun redactedOrNull(value: String?): String = if (value == null) "null" else REDACTED

/** One sign-in method of the signed-in account. [provider] is `email`, `google` or `apple`. */
@Serializable
data class LinkedIdentityDto(
    val provider: String,
    val linkedAt: String,
    /** Masked address (`j•••@gmail.com`); null for the password method and when the provider gave none. */
    val emailHint: String? = null,
)

/** `GET /v1/auth/identities`. */
@Serializable
data class LinkedIdentitiesDto(
    val identities: List<LinkedIdentityDto>,
    val hasPassword: Boolean,
)

/** `DELETE /v1/auth/identities/{provider}`. */
@Serializable
data class UnlinkedIdentityDto(
    val unlinked: Boolean,
)

/**
 * `POST /v1/auth/account-appeal/social/{google|apple}`: a suspended or banned account that signs in
 * with that provider appeals with a fresh provider sign-in instead of a session (the social twin of
 * [AccountAppealRequestDto]).
 */
@Serializable
data class SocialAccountAppealRequestDto(
    val idToken: String,
    val nonce: String,
    val reason: String,
) {
    override fun toString(): String = "SocialAccountAppealRequestDto(idToken=$REDACTED, nonce=$REDACTED, reason=$REDACTED)"
}
