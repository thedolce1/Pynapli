package com.pynapli.server.messaging

import com.pynapli.server.contactblocks.ContactBlockSql
import java.sql.Connection
import java.time.Instant
import java.util.UUID

/**
 * "Hide from people you know", for one viewer (`v.viewer`, `v.token`):
 *  - a conversation in which EVERY other participant hides from the viewer is not theirs (the same rule
 *    as [isHiddenConversationFor]); one with any visible participant is listed, the hidden people
 *    stripped (MessagingDtoMapper) and their messages left out;
 *  - messages from someone hiding from the viewer never make the conversation visible, and nothing such
 *    a person causes moves it up the inbox: when the last bump (`updated_by_user_id`, V121: a message,
 *    a deletion, leaving, an invite naming them) came from them, the viewer's sort time falls back to
 *    their last visible message (or the conversation's creation). A system notice (a timer change) is
 *    shown to the viewer with no sender, so it doesn't make anything visible on its own either.
 * With no token (unit tests, or nobody hides from this viewer) every check is skipped or matches nothing.
 */
private const val HIDDEN_SENDER_OF_HM =
    "EXISTS (SELECT 1 FROM contact_blocks cb_m WHERE cb_m.target_token = v.token AND cb_m.blocker_user_id = hm.sender_user_id " +
        "AND (cb_m.released_at IS NULL OR hm.created_at < cb_m.released_at))"

private const val HIDDEN_SENDER_OF_VM =
    "EXISTS (SELECT 1 FROM contact_blocks cb_v WHERE cb_v.target_token = v.token AND cb_v.blocker_user_id = vm.sender_user_id " +
        "AND (cb_v.released_at IS NULL OR vm.created_at < cb_v.released_at))"

private const val HIDDEN_BUMPER_OF_C =
    "EXISTS (SELECT 1 FROM contact_blocks cb_u WHERE cb_u.target_token = v.token AND cb_u.blocker_user_id = c.updated_by_user_id " +
        "AND (cb_u.released_at IS NULL OR c.updated_at < cb_u.released_at))"

/** The viewer's sort time of conversation `c`; needs `v` in scope. */
internal val VIEWER_SORT_AT_SQL =
    """
    CASE
        WHEN v.token <> '' AND c.updated_by_user_id IS NOT NULL AND $HIDDEN_BUMPER_OF_C
        THEN COALESCE(
            (
                SELECT MAX(vm.created_at) FROM messages vm
                WHERE vm.conversation_id = c.id
                  AND (vm.moderation_status = 'visible' OR vm.sender_user_id = v.viewer)
                  AND NOT $HIDDEN_SENDER_OF_VM
            ),
            c.created_at
        )
        ELSE c.updated_at
    END
    """.trimIndent()

internal fun Connection.listConversationIds(
    userId: UUID,
    limit: Int,
    after: ConversationPageCursor? = null,
    viewerContactToken: String = ContactBlockSql.NO_VIEWER_TOKEN,
): List<UUID> =
    prepareStatement(
        """
        WITH v AS (SELECT ?::uuid AS viewer, ?::text AS token)
        SELECT listed.id
        FROM (
            SELECT c.id, $VIEWER_SORT_AT_SQL AS sort_at
            FROM conversations c
            CROSS JOIN v
            JOIN conversation_participants viewer_cp
              ON viewer_cp.conversation_id = c.id
             AND viewer_cp.user_id = v.viewer
             AND viewer_cp.left_at IS NULL
             AND viewer_cp.invitation_status IN ('active', 'invited')
            WHERE (
               c.created_by_user_id = v.viewer
               OR viewer_cp.invitation_status = 'invited'
               OR $MATCHED_DIRECT_CONVERSATION_FOR_VIEWER_CLAUSE
               OR EXISTS (
                   SELECT 1
                   FROM messages hm
                   WHERE hm.conversation_id = c.id
                     AND (
                         (hm.deleted_at IS NOT NULL AND hm.moderation_status = 'visible')
                         OR (
                             hm.deleted_at IS NULL
                             AND (
                                 hm.moderation_status = 'visible'
                                 OR (hm.moderation_status = 'held' AND hm.sender_user_id = v.viewer)
                             )
                         )
                     )
                     AND NOT $HIDDEN_SENDER_OF_HM
               )
            )
            AND (
                NOT EXISTS (
                    SELECT 1 FROM conversation_participants other_cp
                    WHERE other_cp.conversation_id = c.id
                      AND other_cp.user_id <> v.viewer
                      AND other_cp.left_at IS NULL
                      AND other_cp.invitation_status IN ('active', 'invited')
                )
                OR EXISTS (
                    SELECT 1 FROM conversation_participants other_cp
                    WHERE other_cp.conversation_id = c.id
                      AND other_cp.user_id <> v.viewer
                      AND other_cp.left_at IS NULL
                      AND other_cp.invitation_status IN ('active', 'invited')
                      AND NOT EXISTS (
                          SELECT 1 FROM contact_blocks cb_p
                          WHERE cb_p.target_token = v.token
                            AND cb_p.blocker_user_id = other_cp.user_id
                            AND cb_p.released_at IS NULL
                      )
                )
            )
        ) listed
        WHERE (?::timestamptz IS NULL OR (listed.sort_at, listed.id) < (?::timestamptz, ?::uuid))
        ORDER BY listed.sort_at DESC, listed.id DESC
        LIMIT ?
        """.trimIndent(),
    ).use { statement ->
        val afterTime = after?.updatedAt?.let { java.sql.Timestamp.from(it) }
        statement.setObject(1, userId)
        statement.setString(2, viewerContactToken)
        statement.setTimestamp(3, afterTime)
        statement.setTimestamp(4, afterTime)
        statement.setObject(5, after?.conversationId)
        statement.setInt(6, limit.coerceIn(1, 101))
        statement.executeQuery().use { rs ->
            buildList {
                while (rs.next()) add(rs.getObject("id", UUID::class.java))
            }
        }
    }

/** The viewer's sort time ([VIEWER_SORT_AT_SQL]) of one conversation, or null when it doesn't exist. */
internal fun Connection.loadViewerSortAt(
    userId: UUID,
    conversationId: UUID,
    viewerContactToken: String,
): Instant? =
    prepareStatement(
        """
        WITH v AS (SELECT ?::uuid AS viewer, ?::text AS token)
        SELECT $VIEWER_SORT_AT_SQL AS sort_at
        FROM conversations c
        CROSS JOIN v
        WHERE c.id = ?
        """.trimIndent(),
    ).use { statement ->
        statement.setObject(1, userId)
        statement.setString(2, viewerContactToken)
        statement.setObject(3, conversationId)
        statement.executeQuery().use { rs -> if (rs.next()) rs.getTimestamp("sort_at")?.toInstant() else null }
    }

internal fun Connection.findDirectConversationId(
    userA: UUID,
    userB: UUID,
): UUID? =
    prepareStatement(
        """
        SELECT conversation_id
        FROM direct_conversations
        WHERE LEAST(user_a_id, user_b_id) = LEAST(?::uuid, ?::uuid)
          AND GREATEST(user_a_id, user_b_id) = GREATEST(?::uuid, ?::uuid)
        """.trimIndent(),
    ).use { statement ->
        statement.setObject(1, userA)
        statement.setObject(2, userB)
        statement.setObject(3, userA)
        statement.setObject(4, userB)
        statement.executeQuery().use { rs ->
            if (rs.next()) rs.getObject("conversation_id", UUID::class.java) else null
        }
    }

internal fun Connection.insertDirectConversation(
    conversationId: UUID,
    viewerUserId: UUID,
    targetUserId: UUID,
    origin: String,
) {
    prepareStatement(
        """
        INSERT INTO conversations (id, kind, origin, created_by_user_id)
        VALUES (?, 'direct', ?, ?)
        """.trimIndent(),
    ).use { statement ->
        statement.setObject(1, conversationId)
        statement.setString(2, origin)
        statement.setObject(3, viewerUserId)
        statement.executeUpdate()
    }

    prepareStatement(
        """
        INSERT INTO direct_conversations (conversation_id, user_a_id, user_b_id)
        VALUES (?, LEAST(?::uuid, ?::uuid), GREATEST(?::uuid, ?::uuid))
        """.trimIndent(),
    ).use { statement ->
        statement.setObject(1, conversationId)
        statement.setObject(2, viewerUserId)
        statement.setObject(3, targetUserId)
        statement.setObject(4, viewerUserId)
        statement.setObject(5, targetUserId)
        statement.executeUpdate()
    }

    prepareStatement(
        """
        INSERT INTO conversation_participants (conversation_id, user_id)
        VALUES (?, ?), (?, ?)
        """.trimIndent(),
    ).use { statement ->
        statement.setObject(1, conversationId)
        statement.setObject(2, viewerUserId)
        statement.setObject(3, conversationId)
        statement.setObject(4, targetUserId)
        statement.executeUpdate()
    }
}

internal fun Connection.loadConversation(
    userId: UUID,
    conversationId: UUID,
    // "Hide from people you know": the viewer's token, so the preview and the unread count leave out
    // messages from anyone hiding from the viewer (participants are stripped in MessagingDtoMapper).
    viewerContactToken: String = ContactBlockSql.NO_VIEWER_TOKEN,
): ConversationRecord? {
    val participants = mutableListOf<ConversationParticipantRecord>()
    var kind: String? = null
    var origin: String? = null
    var updatedAt: Instant? = null

    prepareStatement(CONVERSATION_QUERY).use { statement ->
        statement.setObject(1, userId)
        statement.setObject(2, conversationId)
        statement.executeQuery().use { rs ->
            while (rs.next()) {
                kind = rs.getString("kind")
                origin = rs.getString("origin")
                updatedAt = rs.getTimestamp("updated_at").toInstant()
                participants.add(
                    ConversationParticipantRecord(
                        userId = rs.getObject("participant_user_id", UUID::class.java),
                        displayName = rs.getString("display_name"),
                        status = rs.getString("invitation_status"),
                        invitedByUserId = rs.getObject("invited_by_user_id", UUID::class.java),
                        messageExpirySeconds = rs.getInt("message_expiry_seconds").takeUnless { rs.wasNull() },
                        profilePhotoMediaAssetId =
                            rs.getObject("profile_photo_media_asset_id", UUID::class.java),
                    ),
                )
            }
        }
    }

    val effectiveExpirySeconds = participants.effectiveExpirySeconds()
    // Open invites the viewer is party to: all of them for an active participant (inviter or
    // approver), only their own for an invited person.
    val viewerActive = participants.any { it.userId == userId && it.status == "active" }
    val viewerInvites =
        loadOpenPartnerInvites(conversationId, forUpdate = false).filter { invite ->
            viewerActive || invite.invitedPartnerUserId == userId
        }
    // An invited (not yet active) person sees only who is already in the chat and their own invite:
    // never the other open invitees nor who invited anyone else, which would reveal links others hide.
    // Everyone is shown once they are active (an inviter reveals the link to the chat by inviting).
    val visibleParticipants =
        if (viewerActive) {
            participants
        } else {
            participants
                .filter { it.userId == userId || it.status == "active" }
                .map { if (it.userId == userId) it else it.copy(invitedByUserId = null) }
        }
    return ConversationRecord(
        conversationId = conversationId,
        kind = kind ?: return null,
        origin = origin ?: return null,
        participants = visibleParticipants,
        lastMessage = loadLastMessage(userId, conversationId, effectiveExpirySeconds, viewerContactToken),
        unreadCount = loadUnreadCount(userId, conversationId, viewerContactToken),
        // The viewer's own sort time, so a hidden sender's message never moves the thread up (and the
        // inbox cursor, built from this, pages in the same order the listing sorts).
        updatedAt =
            (if (viewerContactToken.isNotEmpty()) loadViewerSortAt(userId, conversationId, viewerContactToken) else null)
                ?: updatedAt
                ?: return null,
        partnerInvite = viewerInvites.primaryFor(userId),
        partnerInvites = viewerInvites,
        viewerExpirySeconds = participants.firstOrNull { it.userId == userId }?.messageExpirySeconds,
        effectiveExpirySeconds = effectiveExpirySeconds,
        newMatch = loadNewMatch(userId, conversationId, viewerContactToken),
    )
}

/**
 * The single `partnerInvite` older clients read: with one open invite it is that invite (as before);
 * with several, the one this viewer acts on first (their own as invitee, else one awaiting their
 * approval, else the newest), matching what an accept or decline without an invite id resolves to.
 */
private fun List<PartnerChatInviteRecord>.primaryFor(userId: UUID): PartnerChatInviteRecord? =
    firstOrNull { it.invitedPartnerUserId == userId }
        ?: firstOrNull { it.inviterUserId != userId && it.thirdPartyStatus == PARTNER_INVITE_PENDING }
        ?: firstOrNull { it.inviterUserId != userId }
        ?: firstOrNull()

/** Strictest (smallest) non-null timer across active participants; null when all are off. */
internal fun List<ConversationParticipantRecord>.effectiveExpirySeconds(): Int? =
    asSequence()
        .filter { it.status == "active" }
        .mapNotNull { it.messageExpirySeconds }
        .minOrNull()

private val CONVERSATION_QUERY =
    """
    SELECT c.kind,
           c.origin,
           c.updated_at,
           cp.user_id AS participant_user_id,
           cp.invitation_status,
           cp.invited_by_user_id,
           cp.message_expiry_seconds,
           -- A thread outlives an account: say so instead of showing the "New member" placeholder.
           CASE
               WHEN u.status = 'deleted' OR u.deleted_at IS NOT NULL THEN 'Deleted member'
               WHEN u.status <> 'active' THEN 'Unavailable member'
               ELSE COALESCE(NULLIF(p.display_name, ''), 'New member')
           END AS display_name,
           profile_photo.media_asset_id AS profile_photo_media_asset_id
    FROM conversations c
    JOIN conversation_participants viewer_cp
      ON viewer_cp.conversation_id = c.id
     AND viewer_cp.user_id = ?
     AND viewer_cp.left_at IS NULL
     AND viewer_cp.invitation_status IN ('active', 'invited')
    JOIN conversation_participants cp
      ON cp.conversation_id = c.id
     AND cp.left_at IS NULL
     AND cp.invitation_status IN ('active', 'invited')
    JOIN users u ON u.id = cp.user_id
    JOIN profiles p ON p.user_id = u.id
    -- Public primary profile photo: same projection as the grid/profile thumbnail, so a chat
    -- avatar shows exactly the photo that participant already exposes on their card (approved,
    -- visible profile-album image), never a private-album asset.
    LEFT JOIN LATERAL (
        SELECT mai.media_asset_id
        FROM media_albums ma
        JOIN media_album_items mai
          ON mai.album_id = ma.id
         AND mai.removed_at IS NULL
        JOIN media_assets m
          ON m.id = mai.media_asset_id
         AND m.owner_user_id = ma.owner_user_id
         AND m.deleted_at IS NULL
        WHERE ma.owner_user_id = cp.user_id
          AND ma.kind = 'profile'
          AND ma.visibility_status = 'visible'
          AND ma.deleted_at IS NULL
          AND m.media_type = 'image'
          AND m.processing_status = 'ready'
          AND m.moderation_status = 'approved'
          AND m.intended_album_kind = 'profile'
        ORDER BY mai.sort_order ASC, mai.added_at DESC, m.id ASC
        LIMIT 1
    ) profile_photo ON true
    WHERE c.id = ?
    ORDER BY cp.joined_at ASC, cp.user_id ASC
    """.trimIndent()
