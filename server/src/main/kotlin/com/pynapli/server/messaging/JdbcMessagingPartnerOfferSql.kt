package com.pynapli.server.messaging

import com.pynapli.server.discovery.CoupleVerifiedFilterSql
import java.sql.Connection
import java.util.UUID

/*
 * "Bring your partner" (MESSAGING_SPEC "Partner Invite Flow"): a per-viewer, read-only offer on a
 * matched conversation, computed in one query. Acting on it is the ordinary partner invite; several
 * invites can be open at once, so each person can bring their own partner straight after a match.
 *
 * Candidates are the linked partners of the conversation's active participants who are not in it and
 * have never been invited to it (pending, accepted or declined: declining is final for the offer):
 *  - the viewer's own partner (offered; not while the viewer is risk-restricted, like the invite);
 *  - another participant's partner the viewer may see (named only), by the Grid/Swipe partner-mini
 *    rule: the owner's partner_visibility allows this viewer
 *    (CoupleVerifiedFilterSql.ownerShowsPartnerToViewer), and the partner is visible, not incognito,
 *    not quarantined/terminated, not hidden by the viewer and not blocked either way with the viewer.
 * Nothing is offered unless the viewer is an active participant of a matched pair's conversation, and
 * nothing at all when:
 *  - a block exists in either direction between any two of the people who would end up together (the
 *    present participants and every candidate). A partner the viewer may not see is never a candidate,
 *    so their blocks never change what the viewer sees;
 *  - the conversation is the later of a Psync together match's two conversations. A together match
 *    (psync_session_matches, with the session's two participants as recorded) opens one conversation
 *    per partner with the target; offers appear only in the one opened first, while both still have
 *    their match. Everyone in a together match already knows the pair, and the rule ignores whether
 *    they are still linked, so it reveals nothing; one partner's solo match never counts.
 */
internal fun Connection.loadPartnerOffers(
    viewerUserId: UUID,
    conversation: ConversationRecord,
): ConversationPartnerOffersRecord? {
    val rows =
        prepareStatement(PARTNER_OFFER_QUERY).use { statement ->
            statement.setObject(1, conversation.conversationId)
            // The viewer, then ownerShowsPartnerToViewer's three viewer placeholders.
            (2..5).forEach { index -> statement.setObject(index, viewerUserId) }
            statement.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            Triple(
                                rs.getObject("owner_user_id", UUID::class.java),
                                rs.getObject("partner_user_id", UUID::class.java),
                                rs.getString("partner_name"),
                            ),
                        )
                    }
                }
            }
        }
    if (rows.isEmpty()) return null
    val (own, others) = rows.partition { (ownerUserId, _, _) -> ownerUserId == viewerUserId }
    return ConversationPartnerOffersRecord(
        bringPartner =
            own.firstOrNull()?.let { (_, partnerUserId, name) -> BringPartnerOfferRecord(partnerUserId, name) },
        participantPartners =
            others.map { (ownerUserId, _, name) -> ParticipantPartnerOfferRecord(ownerUserId, name) },
    )
}

/**
 * Whether the viewer's own linked partner is in the 24-hour cooldown for this thread (after a decline
 * by either side), so clients hide the manual Invite button instead of offering an invite the server
 * would refuse.
 */
internal fun Connection.isOwnPartnerInviteCoolingDown(
    viewerUserId: UUID,
    conversationId: UUID,
): Boolean =
    prepareStatement(
        """
        SELECT EXISTS (
            SELECT 1
            FROM user_profile_links upl
            JOIN partner_chat_invites pci
              ON pci.thread_id = ?
             AND pci.invited_partner_user_id = upl.linked_user_id
             AND (pci.third_party_status = 'declined' OR pci.partner_status = 'declined')
             AND pci.resolved_at > now() - interval '24 hours'
            WHERE upl.user_id = ?
              AND upl.status = 'active'
              AND upl.unlinked_at IS NULL
        )
        """.trimIndent(),
    ).use { statement ->
        statement.setObject(1, conversationId)
        statement.setObject(2, viewerUserId)
        statement.executeQuery().use { rs ->
            rs.next()
            rs.getBoolean(1)
        }
    }

private val PARTNER_OFFER_QUERY =
    """
    WITH args AS (
        SELECT ?::uuid AS conversation_id, ?::uuid AS viewer_id
    ),
    present AS (
        SELECT cp.user_id, cp.invitation_status
        FROM conversation_participants cp
        JOIN args ON cp.conversation_id = args.conversation_id
        WHERE cp.left_at IS NULL
          AND cp.invitation_status IN ('active', 'invited')
    ),
    pair AS (
        SELECT dc.user_a_id, dc.user_b_id, c.created_at, c.id, args.viewer_id
        FROM args
        JOIN direct_conversations dc ON dc.conversation_id = args.conversation_id
        JOIN conversations c ON c.id = dc.conversation_id
        JOIN matches mt
          ON LEAST(mt.user_a, mt.user_b) = LEAST(dc.user_a_id, dc.user_b_id)
         AND GREATEST(mt.user_a, mt.user_b) = GREATEST(dc.user_a_id, dc.user_b_id)
        WHERE EXISTS (SELECT 1 FROM present WHERE user_id = args.viewer_id AND invitation_status = 'active')
    ),
    candidates AS (
        SELECT owner_cp.user_id AS owner_user_id,
               upl.linked_user_id AS partner_user_id,
               COALESCE(NULLIF(partner_profile.display_name, ''), 'Pynapli User') AS partner_name
        FROM present owner_cp
        JOIN pair ON true
        JOIN user_profile_links upl
          ON upl.user_id = owner_cp.user_id
         AND upl.status = 'active'
         AND upl.unlinked_at IS NULL
        JOIN users partner_user
          ON partner_user.id = upl.linked_user_id
         AND partner_user.status = 'active'
         AND partner_user.deleted_at IS NULL
        JOIN profiles partner_profile ON partner_profile.user_id = partner_user.id
        LEFT JOIN discovery_settings partner_ds ON partner_ds.user_id = partner_user.id
        LEFT JOIN discovery_settings owner_ds ON owner_ds.user_id = owner_cp.user_id
        LEFT JOIN account_risk_states partner_risk ON partner_risk.user_id = partner_user.id
        WHERE owner_cp.invitation_status = 'active'
          AND upl.linked_user_id NOT IN (SELECT user_id FROM present)
          AND NOT EXISTS (
              SELECT 1 FROM partner_chat_invites pci
              WHERE pci.thread_id = pair.id
                AND pci.invited_partner_user_id = upl.linked_user_id
          )
          AND (
              (
                  owner_cp.user_id = pair.viewer_id
                  AND NOT EXISTS (
                      SELECT 1 FROM account_risk_states viewer_risk
                      WHERE viewer_risk.user_id = pair.viewer_id
                        AND viewer_risk.tier IN ('quarantine', 'terminate')
                  )
              )
              OR (
                  owner_cp.user_id <> pair.viewer_id
                  AND partner_profile.visibility_status = 'visible'
                  AND partner_ds.incognito = false
                  AND COALESCE(partner_risk.tier, 'clean') NOT IN ('quarantine', 'terminate')
                  AND NOT EXISTS (
                      SELECT 1 FROM user_hidden_profiles hidden
                      WHERE hidden.hider_user_id = pair.viewer_id
                        AND hidden.hidden_user_id = partner_user.id
                  )
                  AND NOT EXISTS (
                      SELECT 1 FROM user_blocks viewer_block
                      WHERE (viewer_block.blocker_user_id = pair.viewer_id AND viewer_block.blocked_user_id = partner_user.id)
                         OR (viewer_block.blocker_user_id = partner_user.id AND viewer_block.blocked_user_id = pair.viewer_id)
                  )
                  AND ${CoupleVerifiedFilterSql.ownerShowsPartnerToViewer(settings = "owner_ds", owner = "owner_cp.user_id")}
              )
          )
    ),
    people AS (
        SELECT user_id FROM present
        UNION
        SELECT partner_user_id FROM candidates
    )
    SELECT owner_user_id, partner_user_id, partner_name
    FROM candidates
    WHERE NOT EXISTS (
        SELECT 1 FROM user_blocks ub
        WHERE ub.blocker_user_id IN (SELECT user_id FROM people)
          AND ub.blocked_user_id IN (SELECT user_id FROM people)
    )
    AND NOT EXISTS (
        SELECT 1
        FROM pair
        JOIN psync_session_matches together
          ON together.kind = 'together'
         AND together.target_user_id IN (pair.user_a_id, pair.user_b_id)
        JOIN psync_session_participants this_partner
          ON this_partner.session_id = together.session_id
         AND this_partner.user_id IN (pair.user_a_id, pair.user_b_id)
         AND this_partner.user_id <> together.target_user_id
        JOIN psync_session_participants other_partner
          ON other_partner.session_id = together.session_id
         AND other_partner.user_id <> this_partner.user_id
        JOIN direct_conversations sibling
          ON LEAST(sibling.user_a_id, sibling.user_b_id) = LEAST(other_partner.user_id, together.target_user_id)
         AND GREATEST(sibling.user_a_id, sibling.user_b_id) = GREATEST(other_partner.user_id, together.target_user_id)
        JOIN conversations sibling_conversation ON sibling_conversation.id = sibling.conversation_id
        JOIN matches sibling_match
          ON LEAST(sibling_match.user_a, sibling_match.user_b) = LEAST(sibling.user_a_id, sibling.user_b_id)
         AND GREATEST(sibling_match.user_a, sibling_match.user_b) = GREATEST(sibling.user_a_id, sibling.user_b_id)
        WHERE (sibling_conversation.created_at, sibling_conversation.id) < (pair.created_at, pair.id)
    )
    ORDER BY owner_user_id
    """.trimIndent()
