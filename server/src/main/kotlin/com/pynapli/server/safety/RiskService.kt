package com.pynapli.server.safety

import java.util.UUID

interface ReportRiskSignalPublisher {
    suspend fun recordReportCreated(
        reportId: UUID,
        reporterUserId: UUID,
        reportedUserId: UUID,
        targetType: String,
        category: String,
    )
}

interface RateLimitRiskSignalPublisher {
    suspend fun recordRateLimitHit(
        userId: UUID,
        action: String,
        count: Long,
        limit: Long,
        windowSeconds: Long,
    )
}

interface MessageReviewRiskSignalPublisher {
    suspend fun recordMessageHeld(
        userId: UUID,
        messageId: UUID,
        reason: String,
    )
}

interface MediaModerationRiskSignalPublisher {
    suspend fun recordSevereMediaModerationFlag(
        reportId: UUID,
        userId: UUID,
        mediaAssetId: UUID,
        albumKind: String,
        reason: String,
        category: String,
    )
}

interface LocationRiskSignalPublisher {
    /** A device-fix location update implies physically impossible travel. */
    suspend fun recordImpossibleTravel(
        userId: UUID,
        distanceKm: Double,
        elapsedSeconds: Long,
        speedKmh: Double,
    )
}

interface VerificationRiskSignalPublisher {
    /** Successful age verification lowers risk (TRUST_AND_SAFETY_SPEC). */
    suspend fun recordAgeVerificationPassed(userId: UUID)
}

interface DeviceRiskSignalPublisher {
    /**
     * A single device hash has created an unusual number of distinct accounts within the
     * detection window (account-farm signal, TRUST_AND_SAFETY_SPEC). Coarse facts only — the
     * device hash itself is never persisted into the risk event metadata.
     */
    suspend fun recordDeviceAccountFarm(
        userId: UUID,
        distinctAccountCount: Int,
        windowHours: Long,
    )
}

class RiskService(
    private val repository: RiskRepository,
) : ReportRiskSignalPublisher,
    RateLimitRiskSignalPublisher,
    MessageReviewRiskSignalPublisher,
    MediaModerationRiskSignalPublisher,
    LocationRiskSignalPublisher,
    VerificationRiskSignalPublisher,
    DeviceRiskSignalPublisher {
    override suspend fun recordReportCreated(
        reportId: UUID,
        reporterUserId: UUID,
        reportedUserId: UUID,
        targetType: String,
        category: String,
    ) {
        repository.recordEvent(
            userId = reportedUserId,
            actorUserId = reporterUserId,
            source = SOURCE_REPORT,
            reason = "report.$category",
            delta = reportDelta(category),
            metadataJson =
                mapOf(
                    "reportId" to reportId.toString(),
                    "targetType" to targetType,
                    "category" to category,
                ).toJsonObjectString(),
        )
    }

    suspend fun getState(userId: UUID): AccountRiskStateRecord? = repository.getState(userId)

    override suspend fun recordRateLimitHit(
        userId: UUID,
        action: String,
        count: Long,
        limit: Long,
        windowSeconds: Long,
    ) {
        repository.recordEvent(
            userId = userId,
            actorUserId = null,
            source = SOURCE_RATE_LIMIT,
            reason = "rate_limit.$action",
            delta = rateLimitDelta(action),
            metadataJson =
                mapOf(
                    "action" to action,
                    "count" to count.toString(),
                    "limit" to limit.toString(),
                    "windowSeconds" to windowSeconds.toString(),
                ).toJsonObjectString(),
        )
    }

    override suspend fun recordMessageHeld(
        userId: UUID,
        messageId: UUID,
        reason: String,
    ) {
        repository.recordEvent(
            userId = userId,
            actorUserId = null,
            source = SOURCE_MESSAGE_REVIEW,
            reason = "message_review.$reason",
            delta = messageReviewDelta(reason),
            metadataJson =
                mapOf(
                    "messageId" to messageId.toString(),
                    "reason" to reason,
                ).toJsonObjectString(),
        )
    }

    override suspend fun recordSevereMediaModerationFlag(
        reportId: UUID,
        userId: UUID,
        mediaAssetId: UUID,
        albumKind: String,
        reason: String,
        category: String,
    ) {
        repository.recordEvent(
            userId = userId,
            actorUserId = null,
            source = SOURCE_MEDIA_MODERATION,
            reason = "media_moderation.$reason",
            delta = 70,
            metadataJson =
                mapOf(
                    "reportId" to reportId.toString(),
                    "mediaAssetId" to mediaAssetId.toString(),
                    "albumKind" to albumKind,
                    "category" to category,
                ).toJsonObjectString(),
        )
    }

    override suspend fun recordImpossibleTravel(
        userId: UUID,
        distanceKm: Double,
        elapsedSeconds: Long,
        speedKmh: Double,
    ) {
        repository.recordEvent(
            userId = userId,
            actorUserId = null,
            source = SOURCE_LOCATION,
            reason = "location.impossible_travel",
            delta = IMPOSSIBLE_TRAVEL_DELTA,
            metadataJson =
                mapOf(
                    // Coarse signal facts only — never exact coordinates
                    // (sensitive-data logging rule).
                    "distanceKm" to "%.0f".format(distanceKm),
                    "elapsedSeconds" to elapsedSeconds.toString(),
                    "speedKmh" to "%.0f".format(speedKmh),
                ).toJsonObjectString(),
        )
    }

    override suspend fun recordAgeVerificationPassed(userId: UUID) {
        repository.recordEvent(
            userId = userId,
            actorUserId = null,
            source = SOURCE_VERIFICATION,
            reason = "verification.age_verified",
            delta = AGE_VERIFIED_DELTA,
        )
    }

    override suspend fun recordDeviceAccountFarm(
        userId: UUID,
        distinctAccountCount: Int,
        windowHours: Long,
    ) {
        repository.recordEvent(
            userId = userId,
            actorUserId = null,
            source = SOURCE_DEVICE,
            reason = "device.account_farm",
            delta = DEVICE_ACCOUNT_FARM_DELTA,
            metadataJson =
                mapOf(
                    // Coarse facts only — never the device hash itself.
                    "accountCount" to distinctAccountCount.toString(),
                    "windowHours" to windowHours.toString(),
                ).toJsonObjectString(),
        )
    }

    companion object {
        const val SOURCE_REPORT = "report"
        const val SOURCE_RATE_LIMIT = "rate_limit"
        const val SOURCE_MESSAGE_REVIEW = "message_review"
        const val SOURCE_MEDIA_MODERATION = "media_moderation"
        const val SOURCE_LOCATION = "location"
        const val SOURCE_VERIFICATION = "verification"
        const val SOURCE_DEVICE = "device"

        const val IMPOSSIBLE_TRAVEL_DELTA = 25

        // Account-farm signal: meaningful but not auto-quarantine on its own; it compounds with
        // other signals toward review/quarantine.
        const val DEVICE_ACCOUNT_FARM_DELTA = 30

        // Verification lowers risk (TRUST_AND_SAFETY_SPEC): the score floor stays
        // at 0 in the repository, so this can never go negative overall.
        const val AGE_VERIFIED_DELTA = -20

        fun reportDelta(category: String): Int =
            when (category) {
                "underage_user" -> 35
                "non_consensual_content" -> 30
                "private_info_doxxing" -> 25
                "harassment_or_threats" -> 20
                "impersonation" -> 18
                "spam_or_fake_profile" -> 15
                "solicitation" -> 15
                "explicit_public_content" -> 12
                else -> 8
            }

        fun rateLimitDelta(action: String): Int =
            when (action) {
                RateLimitedAction.FIRST_MESSAGE -> 6
                RateLimitedAction.ALBUM_REQUEST -> 5
                RateLimitedAction.FLIRT -> 4
                RateLimitedAction.SWIPE_ACTION -> 3
                RateLimitedAction.PROFILE_VIEW -> 3
                RateLimitedAction.REPORT -> 2
                else -> 2
            }

        fun messageReviewDelta(reason: String): Int =
            when (reason) {
                "link_first_message" -> 8
                "duplicate_first_message" -> 6
                else -> 4
            }
    }
}

private fun Map<String, String>.toJsonObjectString(): String =
    entries.joinToString(prefix = "{", postfix = "}") { (key, value) ->
        "\"${key.jsonEscaped()}\":\"${value.jsonEscaped()}\""
    }

private fun String.jsonEscaped(): String =
    buildString(length) {
        this@jsonEscaped.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(char)
            }
        }
    }
