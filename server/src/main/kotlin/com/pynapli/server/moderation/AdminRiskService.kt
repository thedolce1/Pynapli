package com.pynapli.server.moderation

import java.util.UUID

class AdminRiskService(
    private val repository: AdminRiskRepository,
) {
    suspend fun listRiskStates(
        tierFilterText: String?,
        limitText: String?,
        actorId: String,
        reason: String,
    ): List<AdminRiskStateRecord> {
        val tierFilter = tierFilterText?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: "needs_review"
        if (tierFilter !in TIER_FILTERS) throw ModerationException("INVALID_RISK_TIER_FILTER")
        val limit = limitText.toLimitOrThrow()
        return repository.listRiskStates(
            tierFilter = tierFilter,
            limit = limit,
            actorId = actorId.normalizedActorIdOrThrow(),
            reason = reason.normalizedReasonOrThrow(),
        )
    }

    suspend fun listRiskEvents(
        userIdText: String,
        limitText: String?,
        actorId: String,
        reason: String,
    ): List<AdminRiskEventRecord> =
        repository.listRiskEvents(
            userId = userIdText.toUuidOrThrow(),
            limit = limitText.toLimitOrThrow(),
            actorId = actorId.normalizedActorIdOrThrow(),
            reason = reason.normalizedReasonOrThrow(),
        )

    suspend fun clearRisk(
        userIdText: String,
        actorId: String,
        reason: String?,
    ): AdminRiskActionRecord = setRiskTier(userIdText, "clean", actorId, reason)

    suspend fun setRiskTier(
        userIdText: String,
        tierText: String?,
        actorId: String,
        reason: String?,
    ): AdminRiskActionRecord {
        val tier =
            tierText?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
                ?: throw ModerationException("INVALID_RISK_TIER")
        if (tier !in RISK_TIERS) throw ModerationException("INVALID_RISK_TIER")
        return repository.setRiskTier(
            userId = userIdText.toUuidOrThrow(),
            tier = tier,
            actorId = actorId.normalizedActorIdOrThrow(),
            reason = reason.normalizedReasonOrThrow(),
        ) ?: throw ModerationException("RISK_REVIEW_NOT_FOUND")
    }

    suspend fun markRiskReviewed(
        userIdText: String,
        actorId: String,
        reason: String?,
    ): AdminRiskActionRecord =
        repository.markRiskReviewed(
            userId = userIdText.toUuidOrThrow(),
            actorId = actorId.normalizedActorIdOrThrow(),
            reason = reason.normalizedReasonOrThrow(),
        ) ?: throw ModerationException("RISK_REVIEW_NOT_FOUND")

    suspend fun lockAccount(
        userIdText: String,
        actorId: String,
        reason: String?,
    ): AdminRiskActionRecord =
        repository.lockAccount(
            userId = userIdText.toUuidOrThrow(),
            actorId = actorId.normalizedActorIdOrThrow(),
            reason = reason.normalizedReasonOrThrow(),
        ) ?: throw ModerationException("RISK_REVIEW_NOT_FOUND")

    suspend fun banAccount(
        userIdText: String,
        actorId: String,
        reason: String?,
    ): AdminRiskActionRecord =
        repository.banAccount(
            userId = userIdText.toUuidOrThrow(),
            actorId = actorId.normalizedActorIdOrThrow(),
            reason = reason.normalizedReasonOrThrow(),
        ) ?: throw ModerationException("RISK_REVIEW_NOT_FOUND")

    private fun String?.toLimitOrThrow(): Int {
        val normalized = this?.takeIf { it.isNotBlank() } ?: return DEFAULT_LIMIT
        return normalized.toIntOrNull()?.takeIf { it in 1..MAX_LIMIT }
            ?: throw ModerationException("INVALID_REVIEW_LIMIT")
    }

    private fun String.toUuidOrThrow(): UUID = runCatching { UUID.fromString(this) }.getOrElse { throw ModerationException("INVALID_ID") }

    private fun String.normalizedActorIdOrThrow(): String {
        val normalized = trim()
        if (normalized.length !in 2..120) throw ModerationException("INVALID_ADMIN_ACTOR")
        return normalized
    }

    private fun String?.normalizedReasonOrThrow(): String {
        val normalized = this?.trim().orEmpty()
        if (normalized.isEmpty()) throw ModerationException("MODERATION_REASON_REQUIRED")
        if (normalized.length > 500) throw ModerationException("MODERATION_REASON_TOO_LONG")
        return normalized
    }

    companion object {
        private const val DEFAULT_LIMIT = 50
        private const val MAX_LIMIT = 100
        private val RISK_TIERS = setOf("clean", "watch", "friction", "quarantine", "terminate")
        private val TIER_FILTERS = RISK_TIERS + "needs_review" + "all"
    }
}
