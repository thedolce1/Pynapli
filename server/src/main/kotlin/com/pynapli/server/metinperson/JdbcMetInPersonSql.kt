package com.pynapli.server.metinperson

import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * Storage for "Met in person". Every statement binds the service's clock (never `now()`), so tests
 * drive time and the stored dates are the day-truncated values the service chose.
 */
internal object JdbcMetInPersonSql {
    fun Connection.viewerFacts(
        viewerUserId: UUID,
        conversationId: UUID,
    ): MetInPersonViewerFacts? =
        prepareStatement(
            """
            SELECT (u.status = 'active') AS active_account,
                   (p.verified_at IS NOT NULL) AS verified,
                   u.created_at,
                   COALESCE(r.tier, 'clean') AS risk_tier,
                   EXISTS (
                       SELECT 1
                       FROM conversation_participants cp
                       WHERE cp.conversation_id = ?
                         AND cp.user_id = u.id
                         AND cp.invitation_status = 'active'
                         AND cp.left_at IS NULL
                   ) AS active_participant
            FROM users u
            JOIN profiles p ON p.user_id = u.id
            LEFT JOIN account_risk_states r ON r.user_id = u.id
            WHERE u.id = ?
              AND u.deleted_at IS NULL
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, conversationId)
            statement.setObject(2, viewerUserId)
            statement.executeQuery().use { rs ->
                if (!rs.next()) {
                    null
                } else {
                    MetInPersonViewerFacts(
                        activeAccount = rs.getBoolean("active_account"),
                        activeParticipant = rs.getBoolean("active_participant"),
                        verified = rs.getBoolean("verified"),
                        accountCreatedAt = rs.getTimestamp("created_at").toInstant(),
                        riskTier = rs.getString("risk_tier"),
                    )
                }
            }
        }

    /**
     * Facts about every other participant row of the conversation, relative to the viewer. A visible
     * message is a `user` message whose moderation status is `visible` (held and removed messages do
     * not count; a message the sender later deleted still shows the two talked).
     */
    fun Connection.subjectFacts(
        viewerUserId: UUID,
        conversationId: UUID,
    ): List<MetInPersonSubjectFacts> =
        prepareStatement(
            """
            WITH visible_messages AS (
                SELECT m.sender_user_id, m.created_at
                FROM messages m
                WHERE m.conversation_id = ?
                  AND m.message_type = 'user'
                  AND m.moderation_status = 'visible'
            ),
            bounds AS (
                SELECT min(created_at) AS first_at, max(created_at) AS last_at
                FROM visible_messages
            )
            SELECT cp.user_id AS subject_user_id,
                   COALESCE(NULLIF(sp.display_name, ''), 'Pynapli User') AS display_name,
                   (cp.invitation_status = 'active' AND cp.left_at IS NULL) AS active_participant,
                   (sp.verified_at IS NOT NULL) AS subject_verified,
                   (SELECT first_at FROM bounds) AS first_message_at,
                   (SELECT last_at FROM bounds) AS last_message_at,
                   EXISTS (SELECT 1 FROM visible_messages vm WHERE vm.sender_user_id = ?) AS viewer_sent,
                   EXISTS (SELECT 1 FROM visible_messages vm WHERE vm.sender_user_id = cp.user_id) AS subject_sent,
                   EXISTS (
                       SELECT 1
                       FROM user_profile_links l
                       WHERE (l.user_id = ? AND l.linked_user_id = cp.user_id)
                          OR (l.user_id = cp.user_id AND l.linked_user_id = ?)
                   ) AS ever_linked,
                   EXISTS (
                       SELECT 1
                       FROM user_blocks b
                       WHERE b.blocker_user_id = ?
                         AND b.blocked_user_id = cp.user_id
                   ) AS viewer_blocked,
                   partner.partner_user_id,
                   partner.link_id AS partner_link_id,
                   COALESCE(partner.shown, false) AS partner_shown
            FROM conversation_participants cp
            JOIN profiles sp ON sp.user_id = cp.user_id
            JOIN discovery_settings sds ON sds.user_id = cp.user_id
            LEFT JOIN LATERAL (
                SELECT l.linked_user_id AS partner_user_id,
                       -- One canonical row id for the couple (links are stored once per direction).
                       (
                           SELECT c.id
                           FROM user_profile_links c
                           WHERE c.status = 'active'
                             AND c.unlinked_at IS NULL
                             AND (
                                 (c.user_id = cp.user_id AND c.linked_user_id = l.linked_user_id)
                                 OR (c.user_id = l.linked_user_id AND c.linked_user_id = cp.user_id)
                             )
                           ORDER BY c.id
                           LIMIT 1
                       ) AS link_id,
                       -- Both partners' partner-visibility already show the link to this viewer. The
                       -- viewer is photo-verified whenever an offer exists, so verified_only shows it.
                       (
                           (
                               sds.partner_visibility IN ('public', 'verified_only')
                               OR (
                                   sds.partner_visibility = 'matches_only'
                                   AND EXISTS (
                                       SELECT 1 FROM matches mm
                                       WHERE LEAST(mm.user_a, mm.user_b) = LEAST(?::uuid, cp.user_id)
                                         AND GREATEST(mm.user_a, mm.user_b) = GREATEST(?::uuid, cp.user_id)
                                   )
                               )
                           )
                           AND (
                               pds.partner_visibility IN ('public', 'verified_only')
                               OR (
                                   pds.partner_visibility = 'matches_only'
                                   AND EXISTS (
                                       SELECT 1 FROM matches mm
                                       WHERE LEAST(mm.user_a, mm.user_b) = LEAST(?::uuid, l.linked_user_id)
                                         AND GREATEST(mm.user_a, mm.user_b) = GREATEST(?::uuid, l.linked_user_id)
                                   )
                               )
                           )
                       ) AS shown
                FROM user_profile_links l
                JOIN discovery_settings pds ON pds.user_id = l.linked_user_id
                WHERE l.user_id = cp.user_id
                  AND l.status = 'active'
                  AND l.unlinked_at IS NULL
                ORDER BY l.accepted_at DESC NULLS LAST, l.id
                LIMIT 1
            ) partner ON true
            WHERE cp.conversation_id = ?
              AND cp.user_id <> ?
            ORDER BY cp.joined_at, cp.user_id
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, conversationId)
            statement.setObject(2, viewerUserId)
            statement.setObject(3, viewerUserId)
            statement.setObject(4, viewerUserId)
            statement.setObject(5, viewerUserId)
            statement.setObject(6, viewerUserId)
            statement.setObject(7, viewerUserId)
            statement.setObject(8, viewerUserId)
            statement.setObject(9, viewerUserId)
            statement.setObject(10, conversationId)
            statement.setObject(11, viewerUserId)
            statement.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            MetInPersonSubjectFacts(
                                subjectUserId = rs.getObject("subject_user_id", UUID::class.java),
                                displayName = rs.getString("display_name"),
                                activeParticipant = rs.getBoolean("active_participant"),
                                verified = rs.getBoolean("subject_verified"),
                                viewerSent = rs.getBoolean("viewer_sent"),
                                subjectSent = rs.getBoolean("subject_sent"),
                                firstMessageAt = rs.getTimestamp("first_message_at")?.toInstant(),
                                lastMessageAt = rs.getTimestamp("last_message_at")?.toInstant(),
                                everLinkedWithViewer = rs.getBoolean("ever_linked"),
                                viewerBlockedSubject = rs.getBoolean("viewer_blocked"),
                                partnerUserId = rs.getObject("partner_user_id", UUID::class.java),
                                partnerLinkId = rs.getObject("partner_link_id", UUID::class.java),
                                partnerShown = rs.getBoolean("partner_shown"),
                            ),
                        )
                    }
                }
            }
        }

    fun Connection.pairRows(pairTokens: Collection<String>): List<MetInPersonPairRow> {
        if (pairTokens.isEmpty()) return emptyList()
        return prepareStatement(
            """
            SELECT pair_token, subject_user_id, created_at, withdrawn_at, voided_at, met_partner_user_id, link_id
            FROM vouches
            WHERE pair_token = ANY(?)
            """.trimIndent(),
        ).use { statement ->
            statement.setArray(1, createArrayOf("text", pairTokens.toTypedArray()))
            statement.executeQuery().use { rs ->
                buildList { while (rs.next()) add(rs.toPairRow()) }
            }
        }
    }

    fun Connection.countGivenSince(
        voucherToken: String,
        since: Instant,
    ): Int =
        prepareStatement("SELECT count(*) FROM vouches WHERE voucher_token = ? AND created_at >= ?").use { statement ->
            statement.setString(1, voucherToken)
            statement.setTimestamp(2, Timestamp.from(since))
            statement.executeQuery().use { rs ->
                rs.next()
                rs.getInt(1)
            }
        }

    /**
     * Gives (or gives again) one confirmation. A pair is one row forever: a withdrawn or expired row is
     * restored in place with a new date; a standing row is left exactly as it is; an admin void stays.
     */
    fun Connection.upsertVouch(
        subjectUserId: UUID,
        voucherToken: String,
        pairToken: String,
        metPartnerUserId: UUID?,
        linkId: UUID?,
        conversationToken: String,
        day: Instant,
        expiryCutoff: Instant,
    ) {
        prepareStatement(
            """
            INSERT INTO vouches (
                id, subject_user_id, voucher_token, pair_token, met_partner_user_id, link_id,
                conversation_token, created_at
            )
            VALUES (?, ?, ?, ?, ?::uuid, ?::uuid, ?, ?)
            ON CONFLICT (pair_token) DO UPDATE
            SET created_at = EXCLUDED.created_at,
                withdrawn_at = NULL,
                met_partner_user_id = EXCLUDED.met_partner_user_id,
                link_id = EXCLUDED.link_id,
                conversation_token = EXCLUDED.conversation_token
            WHERE vouches.withdrawn_at IS NOT NULL
               OR vouches.created_at <= ?
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, UUID.randomUUID())
            statement.setObject(2, subjectUserId)
            statement.setString(3, voucherToken)
            statement.setString(4, pairToken)
            statement.setObject(5, metPartnerUserId)
            statement.setObject(6, linkId)
            statement.setString(7, conversationToken)
            statement.setTimestamp(8, Timestamp.from(day))
            statement.setTimestamp(9, Timestamp.from(expiryCutoff))
            statement.executeUpdate()
        }
    }

    /** Takes back a standing confirmation younger than the withdrawal window. Silent either way. */
    fun Connection.withdrawVouch(
        pairToken: String,
        day: Instant,
        withdrawWindowStart: Instant,
    ): Int =
        prepareStatement(
            """
            UPDATE vouches
            SET withdrawn_at = ?
            WHERE pair_token = ?
              AND withdrawn_at IS NULL
              AND created_at >= ?
            """.trimIndent(),
        ).use { statement ->
            statement.setTimestamp(1, Timestamp.from(day))
            statement.setString(2, pairToken)
            statement.setTimestamp(3, Timestamp.from(withdrawWindowStart))
            statement.executeUpdate()
        }

    /** The partner half of a couple confirmation, taken back with the person it was given with. */
    fun Connection.withdrawPartnerVouch(
        partnerPairToken: String,
        metPartnerUserId: UUID,
        day: Instant,
    ): Int =
        prepareStatement(
            """
            UPDATE vouches
            SET withdrawn_at = ?
            WHERE pair_token = ?
              AND met_partner_user_id = ?
              AND withdrawn_at IS NULL
            """.trimIndent(),
        ).use { statement ->
            statement.setTimestamp(1, Timestamp.from(day))
            statement.setString(2, partnerPairToken)
            statement.setObject(3, metPartnerUserId)
            statement.executeUpdate()
        }

    private fun ResultSet.toPairRow(): MetInPersonPairRow =
        MetInPersonPairRow(
            pairToken = getString("pair_token"),
            subjectUserId = getObject("subject_user_id", UUID::class.java),
            createdAt = getTimestamp("created_at").toInstant(),
            withdrawnAt = getTimestamp("withdrawn_at")?.toInstant(),
            voidedAt = getTimestamp("voided_at")?.toInstant(),
            metPartnerUserId = getObject("met_partner_user_id", UUID::class.java),
            linkId = getObject("link_id", UUID::class.java),
        )
}
