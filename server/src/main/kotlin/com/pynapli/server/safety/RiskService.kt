package com.pynapli.server.safety

import java.util.UUID

interface ReportRiskSignalPublisher {
    suspend fun recordReportCreated(
        reportId: UUID,
        reporterUserId: UUID,
        reportedUserId: UUID,
        targetType: String,
        category: String
    )
}

interface RateLimitRiskSignalPublisher {
    suspend fun recordRateLimitHit(
        userId: UUID,
        action: String,
        count: Long,
        limit: Long,
        windowSeconds: Long
    )
}

interface MessageReviewRiskSignalPublisher {
    suspend fun recordMessageHeld(
        userId: UUID,
        messageId: UUID,
        reason: String
    )
}

class RiskService(
    private val repository: RiskRepository
) : ReportRiskSignalPublisher, RateLimitRiskSignalPublisher, MessageReviewRiskSignalPublisher {
    override suspend fun recordReportCreated(
        reportId: UUID,
        reporterUserId: UUID,
        reportedUserId: UUID,
        targetType: String,
        category: String
    ) {
        repository.recordEvent(
            userId = reportedUserId,
            actorUserId = reporterUserId,
            source = SOURCE_REPORT,
            reason = "report.$category",
            delta = reportDelta(category),
            metadataJson = mapOf(
                "reportId" to reportId.toString(),
                "targetType" to targetType,
                "category" to category
            ).toJsonObjectString()
        )
    }

    suspend fun getState(userId: UUID): AccountRiskStateRecord? = repository.getState(userId)

    override suspend fun recordRateLimitHit(
        userId: UUID,
        action: String,
        count: Long,
        limit: Long,
        windowSeconds: Long
    ) {
        repository.recordEvent(
            userId = userId,
            actorUserId = null,
            source = SOURCE_RATE_LIMIT,
            reason = "rate_limit.$action",
            delta = rateLimitDelta(action),
            metadataJson = mapOf(
                "action" to action,
                "count" to count.toString(),
                "limit" to limit.toString(),
                "windowSeconds" to windowSeconds.toString()
            ).toJsonObjectString()
        )
    }

    override suspend fun recordMessageHeld(userId: UUID, messageId: UUID, reason: String) {
        repository.recordEvent(
            userId = userId,
            actorUserId = null,
            source = SOURCE_MESSAGE_REVIEW,
            reason = "message_review.$reason",
            delta = messageReviewDelta(reason),
            metadataJson = mapOf(
                "messageId" to messageId.toString(),
                "reason" to reason
            ).toJsonObjectString()
        )
    }

    companion object {
        const val SOURCE_REPORT = "report"
        const val SOURCE_RATE_LIMIT = "rate_limit"
        const val SOURCE_MESSAGE_REVIEW = "message_review"

        fun reportDelta(category: String): Int = when (category) {
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

        fun rateLimitDelta(action: String): Int = when (action) {
            RateLimitedAction.FIRST_MESSAGE -> 6
            RateLimitedAction.ALBUM_REQUEST -> 5
            RateLimitedAction.FLIRT -> 4
            RateLimitedAction.SWIPE_ACTION -> 3
            RateLimitedAction.PROFILE_VIEW -> 3
            RateLimitedAction.REPORT -> 2
            else -> 2
        }

        fun messageReviewDelta(reason: String): Int = when (reason) {
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
