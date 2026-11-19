package com.pynapli.server.messaging

import java.sql.Connection
import java.util.UUID

/*
 * "Bring your partner" (MESSAGING_SPEC "Partner Invite Flow"): a per-viewer, read-only offer on a
 * matched conversation. It never creates anything: the own-partner offer is acted on through the
 * existing POST .../partner-invite, with its existing accept/decline and third-party approval, and the
 * other person's partner is only named, never invitable by the viewer. Every gate below only narrows
 * what the manual invite would allow.
 *
 * No offer at all when:
 *  - the viewer is not an active participant, or the conversation's direct pair has no match;
 *  - any partner invite is open in the conversation (accept/decline act on the newest open invite
 *    only, so the offer keeps invites one at a time);
 *  - a block exists in either direction between any two of the people who would end up in it: the
 *    present (active and invited) participants plus every partner this viewer would be offered or
 *    shown. Partners hidden from the viewer are left out of this and every other check, so a hidden
 *    partner never changes what the viewer sees;
 *  - a sibling conversation already holds these people (Psync: each partner's own conversation with
 *    the same target) and has had a partner invite: one group per couple-pair, never two.
 * A partner is offered (own) or named (another participant's) only when they are not in the
 * conversation and have never been invited to it (pending, accepted or declined: declining is final
 * for the offer). Another participant's partner is named only where that participant's
 * partner_visibility already shows them to the viewer (the Grid/Swipe partner-mini rule).
 */
internal fun Connection.loadPartnerOffers(
    viewerUserId: UUID,
    conversation: ConversationRecord,
): ConversationPartnerOffersRecord? {
    if (conversation.partnerInvite != null) return null
    val activeUserIds =
        conversation.participants
            .filter { it.status == PARTICIPANT_ACTIVE }
            .map { it.userId }
            .distinct()
    if (viewerUserId !in activeUserIds) return null
    if (!isMatchedDirectConversation(conversation.conversationId)) return null

    val partners = loadActiveLinkedPartners(activeUserIds)
    if (partners.isEmpty()) return null
    val presentUserIds = conversation.participants.map { it.userId }.toSet()
    val everInvited = loadEverInvitedPartnerIds(conversation.conversationId)

    fun isNewToConversation(partner: LinkedPartnerRow): Boolean =
        partner.userId != viewerUserId &&
            partner.userId !in presentUserIds &&
            partner.userId !in everInvited

    val viewerPartner = partners[viewerUserId]
    val bringPartner =
        viewerPartner?.takeIf { isNewToConversation(it) && !isSocialActionRestricted(viewerUserId) }
    // Other participants' partners this viewer may see, keyed by that participant.
    val visibleOtherPartners =
        activeUserIds
            .filter { it != viewerUserId && it != viewerPartner?.userId }
            .mapNotNull { participantUserId ->
                partners[participantUserId]
                    ?.takeIf { isNewToConversation(it) }
                    ?.takeIf { isPartnerVisibleToViewer(viewerUserId, participantUserId, it.userId) }
                    ?.let { participantUserId to it }
            }.toMap()
    if (bringPartner == null && visibleOtherPartners.isEmpty()) return null

    val wouldBeInConversation =
        presentUserIds + listOfNotNull(bringPartner?.userId) + visibleOtherPartners.values.map { it.userId }
    if (anyBlockAmong(wouldBeInConversation)) return null
    val knownPartners =
        buildMap<UUID, UUID> {
            viewerPartner?.let { put(viewerUserId, it.userId) }
            visibleOtherPartners.forEach { (participantUserId, partner) -> put(participantUserId, partner.userId) }
        }
    if (hasProgressedSiblingConversation(conversation.conversationId, activeUserIds, knownPartners)) return null
    return ConversationPartnerOffersRecord(
        bringPartner = bringPartner?.let { BringPartnerOfferRecord(it.userId, it.displayName) },
        participantPartners =
            visibleOtherPartners.map { (participantUserId, partner) ->
                ParticipantPartnerOfferRecord(participantUserId, partner.displayName)
            },
    )
}

private const val PARTICIPANT_ACTIVE = "active"

private data class LinkedPartnerRow(
    val userId: UUID,
    val displayName: String,
)

private fun Connection.isMatchedDirectConversation(conversationId: UUID): Boolean =
    prepareStatement(
        """
        SELECT EXISTS (
            SELECT 1
            FROM direct_conversations dc
            JOIN matches mt
              ON LEAST(mt.user_a, mt.user_b) = LEAST(dc.user_a_id, dc.user_b_id)
             AND GREATEST(mt.user_a, mt.user_b) = GREATEST(dc.user_a_id, dc.user_b_id)
            WHERE dc.conversation_id = ?
        )
        """.trimIndent(),
    ).use { statement ->
        statement.setObject(1, conversationId)
        statement.executeQuery().use { rs ->
            rs.next()
            rs.getBoolean(1)
        }
    }

/** Same link rule as [loadActiveLinkedPartnerUserId] (what the manual invite uses), for several users. */
private fun Connection.loadActiveLinkedPartners(userIds: List<UUID>): Map<UUID, LinkedPartnerRow> =
    prepareStatement(
        """
        SELECT upl.user_id,
               upl.linked_user_id,
               COALESCE(NULLIF(linked_profile.display_name, ''), 'Pynapli User') AS display_name
        FROM user_profile_links upl
        JOIN users linked_user
          ON linked_user.id = upl.linked_user_id
         AND linked_user.status = 'active'
         AND linked_user.deleted_at IS NULL
        JOIN profiles linked_profile ON linked_profile.user_id = linked_user.id
        WHERE upl.user_id = ANY(?)
          AND upl.status = 'active'
          AND upl.unlinked_at IS NULL
        ORDER BY upl.user_id, upl.accepted_at DESC NULLS LAST, upl.id
        """.trimIndent(),
    ).use { statement ->
        statement.setArray(1, createArrayOf("uuid", userIds.toTypedArray()))
        statement.executeQuery().use { rs ->
            buildMap<UUID, LinkedPartnerRow> {
                while (rs.next()) {
                    val ownerUserId = rs.getObject("user_id", UUID::class.java)
                    if (ownerUserId !in this) {
                        put(
                            ownerUserId,
                            LinkedPartnerRow(
                                userId = rs.getObject("linked_user_id", UUID::class.java),
                                displayName = rs.getString("display_name"),
                            ),
                        )
                    }
                }
            }
        }
    }

private fun Connection.loadEverInvitedPartnerIds(conversationId: UUID): Set<UUID> =
    prepareStatement(
        "SELECT DISTINCT invited_partner_user_id FROM partner_chat_invites WHERE thread_id = ?",
    ).use { statement ->
        statement.setObject(1, conversationId)
        statement.executeQuery().use { rs ->
            buildSet {
                while (rs.next()) add(rs.getObject(1, UUID::class.java))
            }
        }
    }

private fun Connection.anyBlockAmong(userIds: Set<UUID>): Boolean =
    prepareStatement(
        """
        SELECT EXISTS (
            SELECT 1 FROM user_blocks
            WHERE blocker_user_id = ANY(?)
              AND blocked_user_id = ANY(?)
        )
        """.trimIndent(),
    ).use { statement ->
        val array = createArrayOf("uuid", userIds.toTypedArray())
        statement.setArray(1, array)
        statement.setArray(2, array)
        statement.executeQuery().use { rs ->
            rs.next()
            rs.getBoolean(1)
        }
    }

/** Mirrors RiskPolicyService.isSocialActionRestricted, which refuses the manual invite. */
private fun Connection.isSocialActionRestricted(userId: UUID): Boolean =
    prepareStatement(
        "SELECT EXISTS (SELECT 1 FROM account_risk_states WHERE user_id = ? AND tier IN ('quarantine', 'terminate'))",
    ).use { statement ->
        statement.setObject(1, userId)
        statement.executeQuery().use { rs ->
            rs.next()
            rs.getBoolean(1)
        }
    }

/**
 * Whether [ownerUserId]'s linked partner [partnerUserId] is shown to [viewerUserId]: the partner-mini
 * rule of CoupleVerifiedFilterSql without its verified conditions (active, visible, not incognito, not
 * quarantined/terminated, not hidden by the viewer, and the owner's partner_visibility allows this
 * viewer; 'hidden' never). Blocks are checked separately across everyone in the conversation.
 */
private fun Connection.isPartnerVisibleToViewer(
    viewerUserId: UUID,
    ownerUserId: UUID,
    partnerUserId: UUID,
): Boolean =
    prepareStatement(
        """
        SELECT EXISTS (
            SELECT 1
            FROM users partner_user
            JOIN profiles partner_profile ON partner_profile.user_id = partner_user.id
            JOIN discovery_settings partner_ds ON partner_ds.user_id = partner_user.id
            JOIN discovery_settings owner_ds ON owner_ds.user_id = ?
            LEFT JOIN account_risk_states partner_risk ON partner_risk.user_id = partner_user.id
            WHERE partner_user.id = ?
              AND partner_user.status = 'active'
              AND partner_user.deleted_at IS NULL
              AND partner_profile.visibility_status = 'visible'
              AND partner_ds.incognito = false
              AND COALESCE(partner_risk.tier, 'clean') NOT IN ('quarantine', 'terminate')
              AND NOT EXISTS (
                  SELECT 1 FROM user_hidden_profiles hidden
                  WHERE hidden.hider_user_id = ?
                    AND hidden.hidden_user_id = partner_user.id
              )
              AND (
                  owner_ds.partner_visibility = 'public'
                  OR (
                      owner_ds.partner_visibility = 'verified_only'
                      AND EXISTS (
                          SELECT 1 FROM profiles viewer_profile
                          WHERE viewer_profile.user_id = ? AND viewer_profile.verified_at IS NOT NULL
                      )
                  )
                  OR (
                      owner_ds.partner_visibility = 'matches_only'
                      AND EXISTS (
                          SELECT 1 FROM matches mm
                          WHERE LEAST(mm.user_a, mm.user_b) = LEAST(?::uuid, ?::uuid)
                            AND GREATEST(mm.user_a, mm.user_b) = GREATEST(?::uuid, ?::uuid)
                      )
                  )
              )
        )
        """.trimIndent(),
    ).use { statement ->
        statement.setObject(1, ownerUserId)
        statement.setObject(2, partnerUserId)
        statement.setObject(3, viewerUserId)
        statement.setObject(4, viewerUserId)
        statement.setObject(5, viewerUserId)
        statement.setObject(6, ownerUserId)
        statement.setObject(7, viewerUserId)
        statement.setObject(8, ownerUserId)
        statement.executeQuery().use { rs ->
            rs.next()
            rs.getBoolean(1)
        }
    }

/**
 * Another conversation that has had a partner invite and already holds this conversation's people:
 * only them and their linked partners, with every active participant here present there in person or
 * through their partner. A Psync together match opens one conversation per partner with the same
 * target; once one of them grows, the other stops offering, so the four never get two groups.
 * [knownPartners] (participant to partner) holds only links this viewer may see.
 */
private fun Connection.hasProgressedSiblingConversation(
    conversationId: UUID,
    activeUserIds: List<UUID>,
    knownPartners: Map<UUID, UUID>,
): Boolean {
    val couplePeople = (activeUserIds + knownPartners.values).toSet()
    val membersBySibling =
        prepareStatement(
            """
            SELECT cp.conversation_id, cp.user_id
            FROM conversation_participants cp
            WHERE cp.conversation_id <> ?
              AND cp.left_at IS NULL
              AND cp.invitation_status IN ('active', 'invited')
              AND cp.conversation_id IN (
                  SELECT pci.thread_id
                  FROM partner_chat_invites pci
                  JOIN conversation_participants anchor
                    ON anchor.conversation_id = pci.thread_id
                   AND anchor.user_id = ANY(?)
                   AND anchor.left_at IS NULL
                   AND anchor.invitation_status IN ('active', 'invited')
                  WHERE pci.thread_id <> ?
              )
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, conversationId)
            statement.setArray(2, createArrayOf("uuid", couplePeople.toTypedArray()))
            statement.setObject(3, conversationId)
            statement.executeQuery().use { rs ->
                val rows = mutableMapOf<UUID, MutableSet<UUID>>()
                while (rs.next()) {
                    rows
                        .getOrPut(rs.getObject("conversation_id", UUID::class.java)) { mutableSetOf() }
                        .add(rs.getObject("user_id", UUID::class.java))
                }
                rows
            }
        }
    return membersBySibling.values.any { members ->
        couplePeople.containsAll(members) &&
            activeUserIds.all { userId ->
                val partnerUserId = knownPartners[userId]
                userId in members || (partnerUserId != null && partnerUserId in members)
            }
    }
}
