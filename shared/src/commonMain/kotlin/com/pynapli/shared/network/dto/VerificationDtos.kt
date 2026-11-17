package com.pynapli.shared.network.dto

import kotlinx.serialization.Serializable

@Serializable
data class AgeVerificationRequirementsDto(
    val required: Boolean,
    val enhancedRequired: Boolean,
    val acceptedMethods: List<AgeVerificationMethodDto>,
    val status: AgeVerificationStatusDto? = null,
)

@Serializable
data class AgeVerificationMethodDto(
    val method: String,
    val label: String,
)

@Serializable
data class StartAgeVerificationRequestDto(
    val confirmedAdult: Boolean,
    val method: String? = null,
    val market: String? = null,
)

@Serializable
data class AgeVerificationStatusDto(
    val status: String,
    val method: String? = null,
    val provider: String? = null,
    val requestedAt: String? = null,
    val verifiedAt: String? = null,
    val expiresAt: String? = null,
    val message: String,
    val redirectUrl: String? = null,
    val providerReference: String? = null,
)

@Serializable
data class AgeVerificationCallbackRequestDto(
    val verificationId: String? = null,
    val userId: String? = null,
    val provider: String? = null,
    val providerReference: String? = null,
    val status: String,
    val verifiedAt: String? = null,
    val expiresAt: String? = null,
    val reason: String? = null,
)
