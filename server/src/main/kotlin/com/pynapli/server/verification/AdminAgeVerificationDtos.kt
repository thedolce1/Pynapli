package com.pynapli.server.verification

import kotlinx.serialization.Serializable

/** Body of the admin age-verification review call. Admin-only, so it lives with the server. */
@Serializable
data class AdminAgeVerificationReviewRequestDto(
    val status: String,
    val reason: String? = null,
)
