package com.pynapli.server.messaging

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

class JdbcMessagingRepository(
    private val dataSource: DataSource
) : MessagingRepository {
    private enum class ParticipantStatus {
        None,
        Active,
        Invited,
        Inactive
    }

    private enum class PartnerInviteUserRole {
        None,
        InvitedPartner,
        ThirdPartyApprover
    }

    private data class GridMessagePolicy(
        val allowGridMessages: Boolean,
        val gridMessagePolicy: String
    )

    override suspend fun listConversations(userId: UUID, limit: Int): List<ConversationRecord> =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.listConversationIds(userId, limit).mapNotNull { conversationId ->
                    connection.loadConversation(userId, conversationId)
                }
            }
        }

    override suspend fun getOrCreateDirectConversation(
        viewerUserId: UUID,
        targetUserId: UUID,
        origin: String,
        conversationIdFactory: () -> UUID
    ): ConversationRecord? = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                if (!connection.targetAllowsConversation(viewerUserId, targetUserId, origin)) {
                    connection.rollback()
                    return@withContext null
                }

                val existingId = connection.findDirectConversationId(viewerUserId, targetUserId)
                val conversationId = existingId ?: conversationIdFactory()
                if (existingId == null) {
                    connection.insertDirectConversation(
                        conversationId = conversationId,
                        viewerUserId = viewerUserId,
                        targetUserId = targetUserId,
                        origin = origin
                    )
                }

                connection.commit()
                connection.loadConversation(viewerUserId, conversationId)
            } catch (error: Throwable) {
                connection.rollback()
                throw error
            } finally {
                connection.autoCommit = true
            }
        }
    }

    override suspend fun getConversationDetail(
        userId: UUID,
        conversationId: UUID,
        limit: Int,
        before: MessagePageCursor?
    ): ConversationDetailRecord? = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            val conversation = connection.loadConversation(userId, conversationId) ?: return@withContext null
            val page = connection.loadMessages(userId, conversationId, limit, before)
            ConversationDetailRecord(
                conversation = conversation,
                messages = page.messages,
                nextCursor = page.nextCursor
            )
        }
    }

    override suspend fun markConversationRead(
        userId: UUID,
        conversationId: UUID
    ): ConversationRecord? = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            val updatedRows = connection.markRead(userId, conversationId)
            if (updatedRows == 0) return@withContext null
            connection.loadConversation(userId, conversationId)
        }
    }

    override suspend fun sendMessage(
        userId: UUID,
        conversationId: UUID,
        body: String,
        moderationStatus: String,
        bodyFingerprint: String?,
        safetyReason: String?,
        messageIdFactory: () -> UUID
    ): MessageRecord? = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                if (!connection.isActiveParticipant(userId, conversationId)) {
                    connection.rollback()
                    return@withContext null
                }

                val messageId = messageIdFactory()
                val message = connection.prepareStatement(
                    """
                    INSERT INTO messages (
                        id,
                        conversation_id,
                        sender_user_id,
                        body,
                        moderation_status,
                        body_fingerprint,
                        safety_reason
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    RETURNING id, conversation_id, sender_user_id, body, created_at, moderation_status
                    """.trimIndent()
                ).use { statement ->
                    statement.setObject(1, messageId)
                    statement.setObject(2, conversationId)
                    statement.setObject(3, userId)
                    statement.setString(4, body)
                    statement.setString(5, moderationStatus)
                    statement.setString(6, bodyFingerprint)
                    statement.setString(7, safetyReason)
                    statement.executeQuery().use { rs ->
                        rs.next()
                        rs.toMessageRecord(
                            deliveryStatus = if (moderationStatus == MessageModerationStatus.HELD) {
                                DELIVERY_HELD_FOR_REVIEW
                            } else {
                                DELIVERY_SENT
                            }
                        )
                    }
                }

                if (moderationStatus == MessageModerationStatus.HELD && safetyReason != null) {
                    connection.insertMessageReviewQueue(
                        messageId = message.messageId,
                        senderUserId = userId,
                        conversationId = conversationId,
                        reason = safetyReason
                    )
                }

                connection.prepareStatement(
                    """
                    UPDATE conversations
                    SET updated_at = ?
                    WHERE id = ?
                    """.trimIndent()
                ).use { statement ->
                    statement.setTimestamp(1, Timestamp.from(message.createdAt))
                    statement.setObject(2, conversationId)
                    statement.executeUpdate()
                }

                connection.prepareStatement(
                    """
            UPDATE conversation_participants
            SET last_read_at = ?
            WHERE conversation_id = ?
              AND user_id = ?
              AND left_at IS NULL
              AND invitation_status = 'active'
            """.trimIndent()
                ).use { statement ->
                    statement.setTimestamp(1, Timestamp.from(message.createdAt))
                    statement.setObject(2, conversationId)
                    statement.setObject(3, userId)
                    statement.executeUpdate()
                }

                connection.commit()
                message
            } catch (error: Throwable) {
                connection.rollback()
                throw error
            } finally {
                connection.autoCommit = true
            }
        }
    }

    override suspend fun countRecentMessagesByFingerprint(
        userId: UUID,
        bodyFingerprint: String,
        since: Instant
    ): Int = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT COUNT(*) AS duplicate_count
                FROM messages
                WHERE sender_user_id = ?
                  AND body_fingerprint = ?
                  AND created_at >= ?
                  AND deleted_at IS NULL
                """.trimIndent()
            ).use { statement ->
                statement.setObject(1, userId)
                statement.setString(2, bodyFingerprint)
                statement.setTimestamp(3, Timestamp.from(since))
                statement.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt("duplicate_count")
                }
            }
        }
    }

    override suspend fun hasSentMessageInConversation(userId: UUID, conversationId: UUID): Boolean =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    """
                    SELECT EXISTS (
                        SELECT 1
                        FROM messages m
                        JOIN conversation_participants cp
                          ON cp.conversation_id = m.conversation_id
                         AND cp.user_id = ?
                         AND cp.left_at IS NULL
                         AND cp.invitation_status = 'active'
                        WHERE m.conversation_id = ?
                          AND m.sender_user_id = ?
                          AND m.deleted_at IS NULL
                    )
                    """.trimIndent()
                ).use { statement ->
                    statement.setObject(1, userId)
                    statement.setObject(2, conversationId)
                    statement.setObject(3, userId)
                    statement.executeQuery().use { rs ->
                        rs.next()
                        rs.getBoolean(1)
                    }
                }
            }
        }

    override suspend fun getActiveLinkedPartnerUserId(userId: UUID): UUID? = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT upl.linked_user_id
                FROM user_profile_links upl
                JOIN users linked_user
                  ON linked_user.id = upl.linked_user_id
                 AND linked_user.status = 'active'
                 AND linked_user.deleted_at IS NULL
                WHERE upl.user_id = ?
                  AND upl.status = 'active'
                  AND upl.unlinked_at IS NULL
                LIMIT 1
                """.trimIndent()
            ).use { statement ->
                statement.setObject(1, userId)
                statement.executeQuery().use { rs ->
                    if (rs.next()) rs.getObject("linked_user_id", UUID::class.java) else null
                }
            }
        }
    }

    override suspend fun inviteLinkedPartner(
        inviterUserId: UUID,
        conversationId: UUID,
        partnerUserId: UUID
    ): PartnerInviteResult = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            val previousAutoCommit = connection.autoCommit
            connection.autoCommit = false
            try {
                if (!connection.isActiveParticipant(inviterUserId, conversationId)) {
                    connection.rollback()
                    return@withContext PartnerInviteResult.ConversationNotFound
                }

                when (connection.participantStatus(conversationId, partnerUserId)) {
                    ParticipantStatus.Active -> {
                        connection.rollback()
                        return@withContext PartnerInviteResult.AlreadyInConversation
                    }
                    ParticipantStatus.Invited -> {
                        connection.rollback()
                        return@withContext PartnerInviteResult.AlreadyInvited
                    }
                    ParticipantStatus.None,
                    ParticipantStatus.Inactive -> Unit
                }

                if (connection.partnerInviteCooldownActive(conversationId, partnerUserId)) {
                    connection.rollback()
                    return@withContext PartnerInviteResult.CooldownActive
                }

                val inviteInserted = connection.insertPartnerChatInvite(
                    conversationId = conversationId,
                    inviterUserId = inviterUserId,
                    partnerUserId = partnerUserId
                )
                if (!inviteInserted) {
                    connection.rollback()
                    return@withContext PartnerInviteResult.AlreadyInvited
                }

                connection.upsertInvitedParticipant(
                    conversationId = conversationId,
                    partnerUserId = partnerUserId,
                    inviterUserId = inviterUserId
                )
                connection.markConversationGroup(conversationId)
                val conversation = connection.loadConversation(inviterUserId, conversationId)
                    ?: run {
                        connection.rollback()
                        return@withContext PartnerInviteResult.ConversationNotFound
                    }
                connection.commit()
                PartnerInviteResult.Invited(conversation)
            } catch (error: Throwable) {
                connection.rollback()
                throw error
            } finally {
                connection.autoCommit = previousAutoCommit
            }
        }
    }

    override suspend fun acceptPartnerInvite(
        userId: UUID,
        conversationId: UUID
    ): ConversationDetailRecord? = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            val previousAutoCommit = connection.autoCommit
            connection.autoCommit = false
            try {
                val invite = connection.loadOpenPartnerInvite(conversationId, forUpdate = true)
                    ?: run {
                        connection.rollback()
                        return@withContext null
                    }
                val userRole = connection.partnerInviteUserRole(userId, invite)
                if (userRole == PartnerInviteUserRole.None) {
                    connection.rollback()
                    return@withContext null
                }

                connection.acceptOpenPartnerInvite(conversationId, userId, invite, userRole)
                val detail = connection.loadConversation(userId, conversationId)?.let { conversation ->
                    val page = connection.loadMessages(userId, conversationId, DEFAULT_MESSAGE_PAGE_LIMIT, before = null)
                    ConversationDetailRecord(
                        conversation = conversation,
                        messages = page.messages,
                        nextCursor = page.nextCursor
                    )
                }
                connection.commit()
                detail
            } catch (error: Throwable) {
                connection.rollback()
                throw error
            } finally {
                connection.autoCommit = previousAutoCommit
            }
        }
    }

    override suspend fun declinePartnerInvite(userId: UUID, conversationId: UUID): Boolean = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            val previousAutoCommit = connection.autoCommit
            connection.autoCommit = false
            try {
                val invite = connection.loadOpenPartnerInvite(conversationId, forUpdate = true)
                    ?: run {
                        connection.rollback()
                        return@withContext false
                    }
                val userRole = connection.partnerInviteUserRole(userId, invite)
                if (userRole == PartnerInviteUserRole.None) {
                    connection.rollback()
                    return@withContext false
                }

                connection.declineOpenPartnerInvite(conversationId, invite, userRole)
                connection.commit()
                true
            } catch (error: Throwable) {
                connection.rollback()
                throw error
            } finally {
                connection.autoCommit = previousAutoCommit
            }
        }
    }

    override suspend fun leaveConversation(userId: UUID, conversationId: UUID): Boolean = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            val updated = connection.prepareStatement(
                """
                UPDATE conversation_participants
                SET invitation_status = 'left',
                    invitation_responded_at = COALESCE(invitation_responded_at, now()),
                    left_at = now()
                WHERE conversation_id = ?
                  AND user_id = ?
                  AND left_at IS NULL
                  AND invitation_status IN ('active', 'invited')
                """.trimIndent()
            ).use { statement ->
                statement.setObject(1, conversationId)
                statement.setObject(2, userId)
                statement.executeUpdate()
            }
            if (updated > 0) connection.touchConversation(conversationId)
            updated > 0
        }
    }

    private fun Connection.targetAllowsConversation(viewerUserId: UUID, targetUserId: UUID, origin: String): Boolean =
        when (origin) {
            "grid" -> targetAllowsGridConversation(viewerUserId, targetUserId)
            "swipe" -> targetAllowsSwipeConversation(viewerUserId, targetUserId)
            else -> false
        }

    private fun Connection.targetAllowsGridConversation(viewerUserId: UUID, targetUserId: UUID): Boolean {
        val policy = prepareStatement(
            """
            SELECT ds.allow_grid_messages, ds.grid_message_policy
            FROM users u
            JOIN profiles p ON p.user_id = u.id
            JOIN discovery_settings ds ON ds.user_id = u.id
            WHERE u.id = ?
              AND u.status = 'active'
              AND u.deleted_at IS NULL
              AND p.visibility_status = 'visible'
              AND ds.grid_visible = true
              AND ds.incognito = false
            """.trimIndent()
        ).use { statement ->
            statement.setObject(1, targetUserId)
            statement.executeQuery().use { rs ->
                if (!rs.next()) return false
                GridMessagePolicy(
                    allowGridMessages = rs.getBoolean("allow_grid_messages"),
                    gridMessagePolicy = rs.getString("grid_message_policy")
                )
            }
        }

        return when {
            !policy.allowGridMessages -> false
            policy.gridMessagePolicy == "everyone" -> true
            policy.gridMessagePolicy == "matches_only" -> targetAllowsSwipeConversation(viewerUserId, targetUserId)
            else -> false
        }
    }

    private fun Connection.targetAllowsSwipeConversation(viewerUserId: UUID, targetUserId: UUID): Boolean =
        prepareStatement(
            """
            SELECT EXISTS (
                SELECT 1
                FROM matches m
                JOIN users target
                  ON target.id = ?
                 AND target.status = 'active'
                 AND target.deleted_at IS NULL
                JOIN profiles target_profile
                  ON target_profile.user_id = target.id
                 AND target_profile.visibility_status = 'visible'
                WHERE LEAST(m.user_a, m.user_b) = LEAST(?::uuid, ?::uuid)
                  AND GREATEST(m.user_a, m.user_b) = GREATEST(?::uuid, ?::uuid)
            )
            """.trimIndent()
        ).use { statement ->
            statement.setObject(1, targetUserId)
            statement.setObject(2, viewerUserId)
            statement.setObject(3, targetUserId)
            statement.setObject(4, viewerUserId)
            statement.setObject(5, targetUserId)
            statement.executeQuery().use { rs ->
                rs.next()
                rs.getBoolean(1)
            }
        }

    private fun Connection.listConversationIds(userId: UUID, limit: Int): List<UUID> =
        prepareStatement(
            """
            SELECT c.id
            FROM conversations c
            JOIN conversation_participants viewer_cp
              ON viewer_cp.conversation_id = c.id
             AND viewer_cp.user_id = ?
             AND viewer_cp.left_at IS NULL
             AND viewer_cp.invitation_status IN ('active', 'invited')
            WHERE c.created_by_user_id = ?
               OR viewer_cp.invitation_status = 'invited'
               OR EXISTS (
                   SELECT 1
                   FROM messages m
                   WHERE m.conversation_id = c.id
                     AND m.deleted_at IS NULL
                     AND (
                         m.moderation_status = 'visible'
                         OR (m.moderation_status = 'held' AND m.sender_user_id = ?)
                     )
               )
            ORDER BY c.updated_at DESC, c.id DESC
            LIMIT ?
            """.trimIndent()
        ).use { statement ->
            statement.setObject(1, userId)
            statement.setObject(2, userId)
            statement.setObject(3, userId)
            statement.setInt(4, limit.coerceIn(1, 100))
            statement.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) add(rs.getObject("id", UUID::class.java))
                }
            }
        }

    private fun Connection.findDirectConversationId(userA: UUID, userB: UUID): UUID? =
        prepareStatement(
            """
            SELECT conversation_id
            FROM direct_conversations
            WHERE LEAST(user_a_id, user_b_id) = LEAST(?::uuid, ?::uuid)
              AND GREATEST(user_a_id, user_b_id) = GREATEST(?::uuid, ?::uuid)
            """.trimIndent()
        ).use { statement ->
            statement.setObject(1, userA)
            statement.setObject(2, userB)
            statement.setObject(3, userA)
            statement.setObject(4, userB)
            statement.executeQuery().use { rs ->
                if (rs.next()) rs.getObject("conversation_id", UUID::class.java) else null
            }
        }

    private fun Connection.insertDirectConversation(
        conversationId: UUID,
        viewerUserId: UUID,
        targetUserId: UUID,
        origin: String
    ) {
        prepareStatement(
            """
            INSERT INTO conversations (id, kind, origin, created_by_user_id)
            VALUES (?, 'direct', ?, ?)
            """.trimIndent()
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
            """.trimIndent()
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
            """.trimIndent()
        ).use { statement ->
            statement.setObject(1, conversationId)
            statement.setObject(2, viewerUserId)
            statement.setObject(3, conversationId)
            statement.setObject(4, targetUserId)
            statement.executeUpdate()
        }
    }

    private fun Connection.loadConversation(userId: UUID, conversationId: UUID): ConversationRecord? {
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
                            invitedByUserId = rs.getObject("invited_by_user_id", UUID::class.java)
                        )
                    )
                }
            }
        }

        return ConversationRecord(
            conversationId = conversationId,
            kind = kind ?: return null,
            origin = origin ?: return null,
            participants = participants,
            lastMessage = loadLastMessage(userId, conversationId),
            unreadCount = loadUnreadCount(userId, conversationId),
            updatedAt = updatedAt ?: return null,
            partnerInvite = loadOpenPartnerInvite(conversationId, forUpdate = false)
        )
    }

    private fun Connection.loadLastMessage(userId: UUID, conversationId: UUID): MessageRecord? =
        prepareStatement(
            """
            SELECT id,
                   conversation_id,
                   sender_user_id,
                   body,
                   created_at,
                   moderation_status,
                   CASE
                       WHEN moderation_status = 'held' THEN 'held_for_review'
                       WHEN sender_user_id <> ? THEN 'received'
                       WHEN NOT EXISTS (
                           SELECT 1
                           FROM conversation_participants cp
                           WHERE cp.conversation_id = messages.conversation_id
                             AND cp.user_id <> messages.sender_user_id
                             AND cp.left_at IS NULL
                             AND cp.invitation_status = 'active'
                             AND cp.last_read_at < messages.created_at
                       ) THEN 'read'
                       ELSE 'sent'
                   END AS delivery_status
            FROM messages
            WHERE conversation_id = ?
              AND deleted_at IS NULL
              AND (moderation_status = 'visible' OR (moderation_status = 'held' AND sender_user_id = ?))
            ORDER BY created_at DESC, id DESC
            LIMIT 1
            """.trimIndent()
        ).use { statement ->
            statement.setObject(1, userId)
            statement.setObject(2, conversationId)
            statement.setObject(3, userId)
            statement.executeQuery().use { rs ->
                if (rs.next()) rs.toMessageRecord(deliveryStatus = rs.getString("delivery_status")) else null
            }
        }

    private fun Connection.loadMessages(
        userId: UUID,
        conversationId: UUID,
        limit: Int,
        before: MessagePageCursor?
    ): MessagePageRecord {
        val pageSize = limit.coerceIn(1, 100)
        val query = buildString {
            append(
                """
                SELECT id,
                       conversation_id,
                       sender_user_id,
                       body,
                       created_at,
                       moderation_status,
                       CASE
                           WHEN moderation_status = 'held' THEN 'held_for_review'
                           WHEN sender_user_id <> ? THEN 'received'
                           WHEN NOT EXISTS (
                               SELECT 1
                               FROM conversation_participants cp
                               WHERE cp.conversation_id = messages.conversation_id
                                 AND cp.user_id <> messages.sender_user_id
                                 AND cp.left_at IS NULL
                                 AND cp.invitation_status = 'active'
                                 AND cp.last_read_at < messages.created_at
                           ) THEN 'read'
                           ELSE 'sent'
                       END AS delivery_status
                FROM messages
                WHERE conversation_id = ?
                  AND deleted_at IS NULL
                  AND (moderation_status = 'visible' OR (moderation_status = 'held' AND sender_user_id = ?))
                """.trimIndent()
            )
            append('\n')
            if (before != null) {
                append(
                    """
                      AND (
                          created_at < ?
                          OR (created_at = ? AND id < ?)
                      )
                    """.trimIndent()
                )
                append('\n')
            }
            append(
                """
                ORDER BY created_at DESC, id DESC
                LIMIT ?
                """.trimIndent()
            )
        }

        val newestFirst = prepareStatement(query).use { statement ->
            var index = 1
            statement.setObject(index++, userId)
            statement.setObject(index++, conversationId)
            statement.setObject(index++, userId)
            if (before != null) {
                val beforeTimestamp = Timestamp.from(before.createdAt)
                statement.setTimestamp(index++, beforeTimestamp)
                statement.setTimestamp(index++, beforeTimestamp)
                statement.setObject(index++, before.messageId)
            }
            statement.setInt(index, pageSize + 1)
            statement.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) add(rs.toMessageRecord(deliveryStatus = rs.getString("delivery_status")))
                }
            }
        }

        val pageMessages = newestFirst.take(pageSize)
        return MessagePageRecord(
            messages = pageMessages.asReversed(),
            nextCursor = if (newestFirst.size > pageSize) {
                pageMessages.lastOrNull()?.toCursor()
            } else {
                null
            }
        )
    }

    private fun Connection.isActiveParticipant(userId: UUID, conversationId: UUID): Boolean =
        prepareStatement(
            """
            SELECT EXISTS (
                SELECT 1
                FROM conversation_participants
                WHERE conversation_id = ?
                  AND user_id = ?
                  AND left_at IS NULL
                  AND invitation_status = 'active'
            )
            """.trimIndent()
        ).use { statement ->
            statement.setObject(1, conversationId)
            statement.setObject(2, userId)
            statement.executeQuery().use { rs ->
                rs.next()
                rs.getBoolean(1)
            }
        }

    private fun Connection.markRead(userId: UUID, conversationId: UUID): Int =
        prepareStatement(
            """
            UPDATE conversation_participants
            SET last_read_at = COALESCE(
                (
                    SELECT MAX(created_at)
                    FROM messages
                    WHERE conversation_id = ?
                      AND deleted_at IS NULL
                      AND moderation_status = 'visible'
                ),
                now()
            )
            WHERE conversation_id = ?
              AND user_id = ?
              AND left_at IS NULL
              AND invitation_status IN ('active', 'invited')
            """.trimIndent()
        ).use { statement ->
            statement.setObject(1, conversationId)
            statement.setObject(2, conversationId)
            statement.setObject(3, userId)
            statement.executeUpdate()
        }

    private fun Connection.loadUnreadCount(userId: UUID, conversationId: UUID): Int =
        prepareStatement(
            """
            SELECT COUNT(*) AS unread_count
            FROM messages m
            JOIN conversation_participants cp
              ON cp.conversation_id = m.conversation_id
             AND cp.user_id = ?
             AND cp.left_at IS NULL
             AND cp.invitation_status IN ('active', 'invited')
            WHERE m.conversation_id = ?
              AND m.sender_user_id <> ?
              AND m.deleted_at IS NULL
              AND m.moderation_status = 'visible'
              AND m.created_at > cp.last_read_at
            """.trimIndent()
        ).use { statement ->
            statement.setObject(1, userId)
            statement.setObject(2, conversationId)
            statement.setObject(3, userId)
            statement.executeQuery().use { rs ->
                rs.next()
                rs.getInt("unread_count")
            }
        }

    private fun ResultSet.toMessageRecord(deliveryStatus: String): MessageRecord = MessageRecord(
        messageId = getObject("id", UUID::class.java),
        conversationId = getObject("conversation_id", UUID::class.java),
        senderUserId = getObject("sender_user_id", UUID::class.java),
        body = getString("body"),
        createdAt = getTimestamp("created_at").toInstant(),
        status = getString("moderation_status"),
        deliveryStatus = deliveryStatus
    )

    private fun Connection.insertMessageReviewQueue(
        messageId: UUID,
        senderUserId: UUID,
        conversationId: UUID,
        reason: String
    ) {
        prepareStatement(
            """
            INSERT INTO message_review_queue (
                id,
                message_id,
                sender_user_id,
                conversation_id,
                reason
            )
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (message_id) DO NOTHING
            """.trimIndent()
        ).use { statement ->
            statement.setObject(1, UUID.randomUUID())
            statement.setObject(2, messageId)
            statement.setObject(3, senderUserId)
            statement.setObject(4, conversationId)
            statement.setString(5, reason)
            statement.executeUpdate()
        }
    }

    private fun MessageRecord.toCursor(): MessagePageCursor = MessagePageCursor(
        createdAt = createdAt,
        messageId = messageId
    )

    private fun Connection.participantStatus(conversationId: UUID, userId: UUID): ParticipantStatus =
        prepareStatement(
            """
            SELECT invitation_status, left_at
            FROM conversation_participants
            WHERE conversation_id = ?
              AND user_id = ?
            """.trimIndent()
        ).use { statement ->
            statement.setObject(1, conversationId)
            statement.setObject(2, userId)
            statement.executeQuery().use { rs ->
                if (!rs.next()) {
                    ParticipantStatus.None
                } else {
                    val status = rs.getString("invitation_status")
                    val leftAt = rs.getTimestamp("left_at")
                    when {
                        leftAt != null -> ParticipantStatus.Inactive
                        status == "active" -> ParticipantStatus.Active
                        status == "invited" -> ParticipantStatus.Invited
                        else -> ParticipantStatus.Inactive
                    }
                }
            }
        }

    private fun Connection.upsertInvitedParticipant(
        conversationId: UUID,
        partnerUserId: UUID,
        inviterUserId: UUID
    ) {
        prepareStatement(
            """
            INSERT INTO conversation_participants (
                conversation_id,
                user_id,
                role,
                joined_at,
                left_at,
                last_read_at,
                invitation_status,
                invited_by_user_id,
                invited_at,
                invitation_responded_at
            )
            VALUES (?, ?, 'member', now(), NULL, now(), 'invited', ?, now(), NULL)
            ON CONFLICT (conversation_id, user_id) DO UPDATE
            SET invitation_status = 'invited',
                invited_by_user_id = EXCLUDED.invited_by_user_id,
                invited_at = now(),
                invitation_responded_at = NULL,
                left_at = NULL,
                last_read_at = now()
            WHERE conversation_participants.invitation_status IN ('declined', 'left')
               OR conversation_participants.left_at IS NOT NULL
            """.trimIndent()
        ).use { statement ->
            statement.setObject(1, conversationId)
            statement.setObject(2, partnerUserId)
            statement.setObject(3, inviterUserId)
            statement.executeUpdate()
        }
    }

    private fun Connection.partnerInviteCooldownActive(conversationId: UUID, partnerUserId: UUID): Boolean =
        prepareStatement(
            """
            SELECT EXISTS (
                SELECT 1
                FROM partner_chat_invites
                WHERE thread_id = ?
                  AND invited_partner_user_id = ?
                  AND third_party_status = ?
                  AND resolved_at > now() - interval '24 hours'
            )
            """.trimIndent()
        ).use { statement ->
            statement.setObject(1, conversationId)
            statement.setObject(2, partnerUserId)
            statement.setString(3, PARTNER_INVITE_DECLINED)
            statement.executeQuery().use { rs ->
                rs.next()
                rs.getBoolean(1)
            }
        }

    private fun Connection.insertPartnerChatInvite(
        conversationId: UUID,
        inviterUserId: UUID,
        partnerUserId: UUID
    ): Boolean =
        prepareStatement(
            """
            INSERT INTO partner_chat_invites (
                id,
                thread_id,
                inviter_user_id,
                invited_partner_user_id,
                third_party_status,
                partner_status
            )
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT DO NOTHING
            """.trimIndent()
        ).use { statement ->
            statement.setObject(1, UUID.randomUUID())
            statement.setObject(2, conversationId)
            statement.setObject(3, inviterUserId)
            statement.setObject(4, partnerUserId)
            statement.setString(5, PARTNER_INVITE_PENDING)
            statement.setString(6, PARTNER_INVITE_PENDING)
            statement.executeUpdate() > 0
        }

    private fun Connection.loadOpenPartnerInvite(
        conversationId: UUID,
        forUpdate: Boolean
    ): PartnerChatInviteRecord? {
        val query = buildString {
            append(
                """
                SELECT id,
                       thread_id,
                       inviter_user_id,
                       invited_partner_user_id,
                       third_party_status,
                       partner_status,
                       created_at
                FROM partner_chat_invites
                WHERE thread_id = ?
                  AND resolved_at IS NULL
                ORDER BY created_at DESC, id DESC
                LIMIT 1
                """.trimIndent()
            )
            if (forUpdate) append("\nFOR UPDATE")
        }
        return prepareStatement(query).use { statement ->
            statement.setObject(1, conversationId)
            statement.executeQuery().use { rs ->
                if (rs.next()) rs.toPartnerChatInviteRecord() else null
            }
        }
    }

    private fun Connection.partnerInviteUserRole(
        userId: UUID,
        invite: PartnerChatInviteRecord
    ): PartnerInviteUserRole =
        when {
            userId == invite.invitedPartnerUserId -> PartnerInviteUserRole.InvitedPartner
            userId != invite.inviterUserId && isActiveParticipant(userId, invite.threadId) ->
                PartnerInviteUserRole.ThirdPartyApprover
            else -> PartnerInviteUserRole.None
        }

    private fun Connection.acceptOpenPartnerInvite(
        conversationId: UUID,
        userId: UUID,
        invite: PartnerChatInviteRecord,
        role: PartnerInviteUserRole
    ) {
        val partnerAccepted = invite.partnerStatus == PARTNER_INVITE_ACCEPTED ||
            role == PartnerInviteUserRole.InvitedPartner
        val thirdPartyAccepted = invite.thirdPartyStatus == PARTNER_INVITE_ACCEPTED ||
            role == PartnerInviteUserRole.ThirdPartyApprover
        val fullyAccepted = partnerAccepted && thirdPartyAccepted

        prepareStatement(
            """
            UPDATE partner_chat_invites
            SET partner_status = CASE WHEN ? THEN ? ELSE partner_status END,
                third_party_status = CASE WHEN ? THEN ? ELSE third_party_status END,
                resolved_at = CASE WHEN ? THEN now() ELSE resolved_at END
            WHERE id = ?
              AND resolved_at IS NULL
            """.trimIndent()
        ).use { statement ->
            statement.setBoolean(1, role == PartnerInviteUserRole.InvitedPartner)
            statement.setString(2, PARTNER_INVITE_ACCEPTED)
            statement.setBoolean(3, role == PartnerInviteUserRole.ThirdPartyApprover)
            statement.setString(4, PARTNER_INVITE_ACCEPTED)
            statement.setBoolean(5, fullyAccepted)
            statement.setObject(6, invite.inviteId)
            statement.executeUpdate()
        }

        if (fullyAccepted) {
            prepareStatement(
                """
                UPDATE conversation_participants
                SET invitation_status = 'active',
                    joined_at = now(),
                    last_read_at = now(),
                    invitation_responded_at = now(),
                    left_at = NULL
                WHERE conversation_id = ?
                  AND user_id = ?
                  AND invitation_status = 'invited'
                  AND left_at IS NULL
                """.trimIndent()
            ).use { statement ->
                statement.setObject(1, conversationId)
                statement.setObject(2, invite.invitedPartnerUserId)
                statement.executeUpdate()
            }
        } else if (role == PartnerInviteUserRole.InvitedPartner) {
            prepareStatement(
                """
                UPDATE conversation_participants
                SET invitation_responded_at = now()
                WHERE conversation_id = ?
                  AND user_id = ?
                  AND invitation_status = 'invited'
                  AND left_at IS NULL
                """.trimIndent()
            ).use { statement ->
                statement.setObject(1, conversationId)
                statement.setObject(2, userId)
                statement.executeUpdate()
            }
        }

        markConversationGroup(conversationId)
    }

    private fun Connection.declineOpenPartnerInvite(
        conversationId: UUID,
        invite: PartnerChatInviteRecord,
        role: PartnerInviteUserRole
    ) {
        prepareStatement(
            """
            UPDATE partner_chat_invites
            SET partner_status = CASE WHEN ? THEN ? ELSE partner_status END,
                third_party_status = CASE WHEN ? THEN ? ELSE third_party_status END,
                resolved_at = now()
            WHERE id = ?
              AND resolved_at IS NULL
            """.trimIndent()
        ).use { statement ->
            statement.setBoolean(1, role == PartnerInviteUserRole.InvitedPartner)
            statement.setString(2, PARTNER_INVITE_DECLINED)
            statement.setBoolean(3, role == PartnerInviteUserRole.ThirdPartyApprover)
            statement.setString(4, PARTNER_INVITE_DECLINED)
            statement.setObject(5, invite.inviteId)
            statement.executeUpdate()
        }

        prepareStatement(
            """
            UPDATE conversation_participants
            SET invitation_status = 'declined',
                invitation_responded_at = now(),
                left_at = now()
            WHERE conversation_id = ?
              AND user_id = ?
              AND invitation_status = 'invited'
              AND left_at IS NULL
            """.trimIndent()
        ).use { statement ->
            statement.setObject(1, conversationId)
            statement.setObject(2, invite.invitedPartnerUserId)
            statement.executeUpdate()
        }
        touchConversation(conversationId)
    }

    private fun ResultSet.toPartnerChatInviteRecord(): PartnerChatInviteRecord = PartnerChatInviteRecord(
        inviteId = getObject("id", UUID::class.java),
        threadId = getObject("thread_id", UUID::class.java),
        inviterUserId = getObject("inviter_user_id", UUID::class.java),
        invitedPartnerUserId = getObject("invited_partner_user_id", UUID::class.java),
        thirdPartyStatus = getString("third_party_status"),
        partnerStatus = getString("partner_status"),
        createdAt = getTimestamp("created_at").toInstant()
    )

    private fun Connection.markConversationGroup(conversationId: UUID) {
        prepareStatement(
            """
            UPDATE conversations
            SET kind = 'group',
                updated_at = now()
            WHERE id = ?
            """.trimIndent()
        ).use { statement ->
            statement.setObject(1, conversationId)
            statement.executeUpdate()
        }
    }

    private fun Connection.touchConversation(conversationId: UUID) {
        prepareStatement(
            """
            UPDATE conversations
            SET updated_at = now()
            WHERE id = ?
            """.trimIndent()
        ).use { statement ->
            statement.setObject(1, conversationId)
            statement.executeUpdate()
        }
    }

    companion object {
        private const val DEFAULT_MESSAGE_PAGE_LIMIT = 50
        private const val DELIVERY_SENT = "sent"
        private const val DELIVERY_HELD_FOR_REVIEW = "held_for_review"
        private const val PARTNER_INVITE_PENDING = "pending"
        private const val PARTNER_INVITE_ACCEPTED = "accepted"
        private const val PARTNER_INVITE_DECLINED = "declined"

        private val CONVERSATION_QUERY = """
            SELECT c.kind,
                   c.origin,
                   c.updated_at,
                   cp.user_id AS participant_user_id,
                   cp.invitation_status,
                   cp.invited_by_user_id,
                   COALESCE(NULLIF(p.display_name, ''), 'Pynapli User') AS display_name
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
            WHERE c.id = ?
            ORDER BY cp.joined_at ASC, cp.user_id ASC
        """.trimIndent()
    }
}
