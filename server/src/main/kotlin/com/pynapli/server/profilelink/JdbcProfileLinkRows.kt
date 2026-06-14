package com.pynapli.server.profilelink

import java.sql.ResultSet
import java.util.UUID

internal fun ResultSet.toJdbcProfileLinkRecord(direction: String): ProfileLinkRecord =
    ProfileLinkRecord(
        linkId = getObject("id", UUID::class.java),
        otherUserId = getObject("other_user_id", UUID::class.java),
        otherDisplayName = getString("other_display_name"),
        otherPhotoMediaAssetId = getObject("other_photo_media_asset_id", UUID::class.java),
        status = getString("status"),
        direction = direction,
        createdAt = getTimestamp("created_at").toInstant(),
        acceptedAt = getTimestamp("accepted_at")?.toInstant(),
        unlinkedAt = getTimestamp("unlinked_at")?.toInstant(),
    )

internal fun ResultSet.toJdbcPendingInvitationRecord(): PendingProfileLinkInvitationRecord =
    PendingProfileLinkInvitationRecord(
        linkId = getObject("id", UUID::class.java),
        requesterUserId = getObject("user_id", UUID::class.java),
        recipientUserId = getObject("linked_user_id", UUID::class.java),
    )

internal fun ResultSet.toJdbcProfileLinkCooldownRecord(): ProfileLinkCooldownRecord =
    ProfileLinkCooldownRecord(
        userId = getObject("user_id", UUID::class.java),
        linkedUserId = getObject("linked_user_id", UUID::class.java),
        unlinkedAt = getTimestamp("unlinked_at").toInstant(),
    )
