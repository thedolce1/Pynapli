package com.pynapli.shared.network.dto

import kotlinx.serialization.Serializable

/**
 * Answer to `POST /v1/auth/email/signup` (ONBOARDING_AUTH_SPEC, "Membership privacy"). The same
 * shape, status and values whether or not the address already has an account: no session is
 * created here. The device keeps [signupToken] (random, never shown to the person) and sends it
 * back with the emailed code to `/v1/auth/email/signup/verify`, or to `/resend`; without it nobody
 * else can finish this sign-up even if they know the address and see the code.
 */
@Serializable
data class EmailSignupStartedDto(
    val verificationRequired: Boolean = true,
    val signupToken: String,
    val codeLength: Int = 6,
    val codeExpiresInSeconds: Long = 1_800,
)

@Serializable
data class EmailSignupVerifyRequestDto(
    val email: String,
    val code: String,
    val signupToken: String,
    val deviceId: String? = null,
    val platform: String? = null,
    val appVersion: String? = null,
)

@Serializable
data class EmailSignupResendRequestDto(
    val email: String,
    val signupToken: String,
    val deviceId: String? = null,
)

/** Same answer whether or not a code was actually sent. */
@Serializable
data class EmailSignupCodeResentDto(
    val sent: Boolean = true,
)

/** Local development only (`/v1/dev/...`): the last sign-up code the local file mailer "sent". */
@Serializable
data class DevSignupCodeRequestDto(
    val email: String,
)

@Serializable
data class DevSignupCodeDto(
    val code: String? = null,
    val alreadyRegisteredNoticeSent: Boolean = false,
)
