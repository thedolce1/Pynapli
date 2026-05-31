package com.pynapli.server.activity

import java.sql.ResultSet
import java.util.UUID

internal object JdbcActivityRowMappers {
    fun activityEvent(rs: ResultSet): ActivityEventRecord {
        val distanceMeters = rs.getDouble("distance_meters").takeUnless { rs.wasNull() }
        return ActivityEventRecord(
            eventId = rs.getObject("event_id", UUID::class.java),
            type = rs.getString("event_type"),
            actorUserId = rs.getObject("actor_user_id", UUID::class.java),
            actorDisplayName = rs.getString("actor_display_name"),
            actorProfilePhotoMediaAssetId = rs.getObject("actor_profile_photo_media_asset_id", UUID::class.java),
            count = rs.getInt("aggregate_count"),
            occurredAt = rs.getTimestamp("occurred_at").toInstant(),
            seen = rs.getBoolean("seen"),
            showDistance = rs.getBoolean("actor_show_distance"),
            distanceMeters = distanceMeters,
            albumRequestStatus = rs.getString("album_request_status"),
            matchId = rs.getString("match_id"),
            conversationId = rs.getString("conversation_id"),
        )
    }

    fun summary(rs: ResultSet): ActivitySummaryRecord =
        ActivitySummaryRecord(
            profileViewCount = rs.getInt("profile_view_count"),
            flirtCount = rs.getInt("flirt_count"),
            likeCount = rs.getInt("like_count"),
            matchCount = rs.getInt("match_count"),
            albumRequestCount = rs.getInt("album_request_count"),
            partnerInviteCount = rs.getInt("partner_invite_count"),
            latestBadgeOccurredAt = rs.getTimestamp("latest_badge_occurred_at")?.toInstant(),
        )
}
