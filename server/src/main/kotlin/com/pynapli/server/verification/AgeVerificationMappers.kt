package com.pynapli.server.verification

import com.pynapli.shared.network.dto.AgeVerificationStatusDto
import java.time.Instant

internal fun AgeVerificationRecord.toDto(profileAgeVerifiedAt: Instant?): AgeVerificationStatusDto {
    val effectiveVerifiedAt = verifiedAt ?: profileAgeVerifiedAt
    val effectiveStatus = if (effectiveVerifiedAt != null && status != AGE_STATUS_REJECTED) AGE_STATUS_VERIFIED else status
    return AgeVerificationStatusDto(
        status = effectiveStatus,
        method = method,
        provider = provider,
        requestedAt = requestedAt.toString(),
        verifiedAt = effectiveVerifiedAt?.toString(),
        expiresAt = expiresAt?.toString(),
        message = ageVerificationMessageFor(effectiveStatus),
    )
}

internal fun verifiedProfileStatus(verifiedAt: Instant): AgeVerificationStatusDto =
    AgeVerificationStatusDto(
        status = AGE_STATUS_VERIFIED,
        method = null,
        provider = null,
        requestedAt = null,
        verifiedAt = verifiedAt.toString(),
        expiresAt = null,
        message = ageVerificationMessageFor(AGE_STATUS_VERIFIED),
    )

private fun ageVerificationMessageFor(status: String): String =
    when (status) {
        AGE_STATUS_VERIFIED -> "Age verification is complete."
        AGE_STATUS_PENDING -> "Age verification is pending review."
        AGE_STATUS_REJECTED -> "Age verification was not approved."
        else -> "Age verification has not started."
    }
