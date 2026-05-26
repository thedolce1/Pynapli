package com.pynapli.server.moderation

import java.sql.ResultSet
import java.util.UUID

internal fun ResultSet.toAdminRiskStateRecord(): AdminRiskStateRecord =
    AdminRiskStateRecord(
        userId = getObject("user_id", UUID::class.java),
        displayName = getString("display_name"),
        score = getInt("score"),
        tier = getString("tier"),
        lastEventAt = getTimestamp("last_event_at")?.toInstant(),
        lastReviewedAt = getTimestamp("last_reviewed_at")?.toInstant(),
        updatedAt = getTimestamp("updated_at").toInstant()
    )

internal fun ResultSet.toAdminRiskEventRecord(): AdminRiskEventRecord =
    AdminRiskEventRecord(
        eventId = getObject("id", UUID::class.java),
        userId = getObject("user_id", UUID::class.java),
        actorUserId = getObject("actor_user_id", UUID::class.java),
        source = getString("source"),
        reason = getString("reason"),
        delta = getInt("delta"),
        scoreAfter = getInt("score_after"),
        tierAfter = getString("tier_after"),
        metadataJson = getString("metadata_json"),
        createdAt = getTimestamp("created_at").toInstant()
    )

internal fun ResultSet.toAdminRiskActionRecord(): AdminRiskActionRecord =
    AdminRiskActionRecord(
        userId = getObject("user_id", UUID::class.java),
        score = getInt("score"),
        tier = getString("tier"),
        lastReviewedAt = getTimestamp("last_reviewed_at")?.toInstant(),
        updatedAt = getTimestamp("updated_at").toInstant()
    )
