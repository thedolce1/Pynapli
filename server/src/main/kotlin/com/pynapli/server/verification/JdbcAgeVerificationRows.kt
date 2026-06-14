package com.pynapli.server.verification

import java.sql.ResultSet
import java.util.UUID

internal fun ResultSet.toAgeVerificationProfileState(): AgeVerificationProfileState =
    AgeVerificationProfileState(
        userId = getObject("user_id", UUID::class.java),
        birthdate = getDate("birthdate")?.toLocalDate(),
        ageVerifiedAt = getTimestamp("age_verified_at")?.toInstant(),
    )

internal fun ResultSet.toAgeVerificationRecord(): AgeVerificationRecord =
    AgeVerificationRecord(
        id = getObject("id", UUID::class.java),
        userId = getObject("user_id", UUID::class.java),
        market = getString("market"),
        method = getString("method"),
        provider = getString("provider"),
        providerReference = getString("provider_reference"),
        status = getString("status"),
        requestedAt = getTimestamp("requested_at").toInstant(),
        verifiedAt = getTimestamp("verified_at")?.toInstant(),
        expiresAt = getTimestamp("expires_at")?.toInstant(),
        reviewedAt = getTimestamp("reviewed_at")?.toInstant(),
        reviewerId = getString("reviewer_id"),
        reviewReason = getString("review_reason"),
    )
