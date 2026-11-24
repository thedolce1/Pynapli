package com.pynapli.server.metinperson

import com.pynapli.server.contactblocks.ContactBlockSql
import com.pynapli.server.discovery.CoupleVerifiedFilterSql
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * Storage for "Met in person". Rule times come from the service's clock (never `now()`), so tests drive
 * time and the stored dates are the day-truncated values the service chose.
 */
internal object JdbcMetInPersonSql {
    private val logger = LoggerFactory.getLogger(JdbcMetInPersonSql::class.java)

    /**
     * True when the configured key reproduces the stored check value (stored on first use). A mismatch
     * means the key was lost or rotated: callers refuse to read or write, so caps cannot reset and
     * badges cannot all turn off silently.
     */
    fun Connection.keyMatches(
        tokens: MetInPersonTokens,
        now: Instant,
    ): Boolean {
        prepareStatement(
            """
            INSERT INTO met_in_person_key (id, key_version, check_value, created_at)
            VALUES (1, ?, ?, ?)
            ON CONFLICT (id) DO NOTHING
            """.trimIndent(),
        ).use { statement ->
            statement.setShort(1, MetInPersonTokens.KEY_VERSION)
            statement.setString(2, tokens.checkValue())
            statement.setTimestamp(3, Timestamp.from(now))
            statement.executeUpdate()
        }
        val stored =
            prepareStatement("SELECT check_value FROM met_in_person_key WHERE id = 1").use { statement ->
                statement.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
            }
        val matches = stored == tokens.checkValue()
        if (!matches) logger.warn("Met in person key check failed; refusing to run")
        return matches
    }

    /**
     * Startup: the stored check value, read only (never stored here), so a server configured with a
     * different K3 is caught before it serves anything. Null before the feature was first used.
     */
    fun Connection.storedKeyCheckValue(): String? =
        prepareStatement("SELECT check_value FROM met_in_person_key WHERE id = 1").use { statement ->
            statement.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }

    fun Connection.viewerFacts(
        viewerUserId: UUID,
        conversationId: UUID,
    ): MetInPersonViewerFacts? =
        prepareStatement(
            """
            SELECT (u.status = 'active') AS active_account,
                   (p.verified_at IS NOT NULL) AS verified,
                   u.created_at,
                   EXISTS (
                       SELECT 1 FROM conversation_participants cp
                       WHERE cp.conversation_id = ?
                         AND cp.user_id = u.id
                         AND cp.invitation_status = 'active'
                         AND cp.left_at IS NULL
                   ) AS active_participant,
                   EXISTS (
                       SELECT 1 FROM conversation_participants cp
                       WHERE cp.conversation_id = ?
                         AND cp.user_id = u.id
                   ) AS participant
            FROM users u
            JOIN profiles p ON p.user_id = u.id
            WHERE u.id = ?
              AND u.deleted_at IS NULL
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, conversationId)
            statement.setObject(2, conversationId)
            statement.setObject(3, viewerUserId)
            statement.executeQuery().use { rs ->
                if (!rs.next()) {
                    null
                } else {
                    MetInPersonViewerFacts(
                        activeAccount = rs.getBoolean("active_account"),
                        activeParticipant = rs.getBoolean("active_participant"),
                        participant = rs.getBoolean("participant"),
                        verified = rs.getBoolean("verified"),
                        accountCreatedAt = rs.getTimestamp("created_at").toInstant(),
                    )
                }
            }
        }

    private val SUBJECT_FACTS_SQL =
        """
        WITH viewer_cp AS (
            SELECT COALESCE(vcp.invitation_responded_at, vcp.joined_at) AS active_from
            FROM conversation_participants vcp
            WHERE vcp.conversation_id = ?
              AND vcp.user_id = ?
        )
        SELECT cp.user_id AS subject_user_id,
               COALESCE(NULLIF(sp.display_name, ''), 'Pynapli User') AS display_name,
               (cp.invitation_status = 'active' AND cp.left_at IS NULL) AS active_participant,
               -- The rows messaging's conversation load holds (contact-block conversation rule).
               (cp.invitation_status IN ('active', 'invited') AND cp.left_at IS NULL) AS current_participant,
               (sp.verified_at IS NOT NULL) AS subject_verified,
               pair_messages.first_at,
               pair_messages.last_at,
               COALESCE(pair_messages.viewer_sent, false) AS viewer_sent,
               COALESCE(pair_messages.subject_sent, false) AS subject_sent,
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
        CROSS JOIN viewer_cp
        -- Only messages between these two, sent after both were in the conversation.
        LEFT JOIN LATERAL (
            SELECT min(m.created_at) AS first_at,
                   max(m.created_at) AS last_at,
                   bool_or(m.sender_user_id = ?) AS viewer_sent,
                   bool_or(m.sender_user_id = cp.user_id) AS subject_sent
            FROM messages m
            WHERE m.conversation_id = cp.conversation_id
              AND m.message_type = 'user'
              AND m.moderation_status = 'visible'
              AND m.sender_user_id IN (?, cp.user_id)
              AND m.created_at >= GREATEST(viewer_cp.active_from, COALESCE(cp.invitation_responded_at, cp.joined_at))
        ) pair_messages ON true
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
                   (
                       ${CoupleVerifiedFilterSql.partnerShownToViewer(
            partnerUser = "mip_partner_user",
            partnerProfile = "mip_partner_profile",
            partnerSettings = "mip_partner_ds",
            partnerRisk = "mip_partner_risk",
            ownerSettings = "sds",
            owner = "cp.user_id",
        )}
                   ) AS shown
            FROM user_profile_links l
            JOIN users mip_partner_user ON mip_partner_user.id = l.linked_user_id
            JOIN profiles mip_partner_profile ON mip_partner_profile.user_id = l.linked_user_id
            JOIN discovery_settings mip_partner_ds ON mip_partner_ds.user_id = l.linked_user_id
            LEFT JOIN account_risk_states mip_partner_risk ON mip_partner_risk.user_id = l.linked_user_id
            WHERE l.user_id = cp.user_id
              AND l.status = 'active'
              AND l.unlinked_at IS NULL
            ORDER BY l.accepted_at DESC NULLS LAST, l.id
            LIMIT 1
        ) partner ON true
        WHERE cp.conversation_id = ?
          AND cp.user_id <> ?
        ORDER BY cp.joined_at, cp.user_id
        """.trimIndent()

    /**
     * Facts about every other participant row of the conversation, relative to the viewer. Placeholders
     * in text order: viewer_cp (conversation, viewer); ever_linked (viewer twice); viewer_blocked
     * (viewer); pair_messages (viewer twice); the shared partner rule (viewer six times); the WHERE
     * (conversation, viewer).
     */
    fun Connection.subjectFacts(
        viewerUserId: UUID,
        conversationId: UUID,
    ): List<MetInPersonSubjectFacts> =
        prepareStatement(SUBJECT_FACTS_SQL).use { statement ->
            var index = 1
            statement.setObject(index++, conversationId)
            statement.setObject(index++, viewerUserId)
            repeat(2 + 1 + 2 + 6) { statement.setObject(index++, viewerUserId) }
            statement.setObject(index++, conversationId)
            statement.setObject(index, viewerUserId)
            statement.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            MetInPersonSubjectFacts(
                                subjectUserId = rs.getObject("subject_user_id", UUID::class.java),
                                displayName = rs.getString("display_name"),
                                activeParticipant = rs.getBoolean("active_participant"),
                                currentParticipant = rs.getBoolean("current_participant"),
                                verified = rs.getBoolean("subject_verified"),
                                viewerSent = rs.getBoolean("viewer_sent"),
                                subjectSent = rs.getBoolean("subject_sent"),
                                firstMessageAt = rs.getTimestamp("first_at")?.toInstant(),
                                lastMessageAt = rs.getTimestamp("last_at")?.toInstant(),
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

    /**
     * Everyone [userId] has ever shared a conversation with (left or not; participant rows are never deleted)
     * and who is not hiding from them ("Hide from people you know", active rows): main's person-level
     * fragment [ContactBlockSql.notHiddenFromViewer], bound to [viewerContactToken]. For what the person
     * is shown (their export); deletion and the cap use [conversationPartners], which must see everyone.
     */
    fun Connection.conversationPartnersVisibleTo(
        userId: UUID,
        viewerContactToken: String,
    ): Set<UUID> =
        prepareStatement(
            """
            SELECT DISTINCT b.user_id
            FROM conversation_participants a
            JOIN conversation_participants b
              ON b.conversation_id = a.conversation_id
             AND b.user_id <> a.user_id
            WHERE a.user_id = ?
              AND ${ContactBlockSql.notHiddenFromViewer("b.user_id")}
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, userId)
            statement.setString(2, viewerContactToken)
            statement.executeQuery().use { rs -> buildSet { while (rs.next()) add(rs.getObject(1, UUID::class.java)) } }
        }

    /** Everyone [userId] has ever shared a conversation with (left or not; participant rows are never deleted). */
    fun Connection.conversationPartners(userId: UUID): Set<UUID> =
        prepareStatement(
            """
            SELECT DISTINCT b.user_id
            FROM conversation_participants a
            JOIN conversation_participants b
              ON b.conversation_id = a.conversation_id
             AND b.user_id <> a.user_id
            WHERE a.user_id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, userId)
            statement.executeQuery().use { rs -> buildSet { while (rs.next()) add(rs.getObject(1, UUID::class.java)) } }
        }

    /** True when [userId] has a participant row (left or not) in [conversationId]. */
    fun Connection.participated(
        conversationId: UUID,
        userId: UUID,
    ): Boolean =
        prepareStatement(
            "SELECT EXISTS (SELECT 1 FROM conversation_participants WHERE conversation_id = ? AND user_id = ?)",
        ).use { statement ->
            statement.setObject(1, conversationId)
            statement.setObject(2, userId)
            statement.executeQuery().use { rs -> rs.next() && rs.getBoolean(1) }
        }

    /** Rows by pair token. */
    fun Connection.pairRows(pairTokens: Collection<String>): Map<String, MetInPersonPairRow> {
        if (pairTokens.isEmpty()) return emptyMap()
        return prepareStatement(
            """
            SELECT pair_token, created_at, withdrawn_at, voided_at, partner_token, link_token
            FROM vouches
            WHERE pair_token = ANY(?)
            """.trimIndent(),
        ).use { statement ->
            statement.setArray(1, createArrayOf("text", pairTokens.toTypedArray()))
            statement.executeQuery().use { rs ->
                buildMap { while (rs.next()) rs.toPairRow().let { put(it.pairToken, it) } }
            }
        }
    }

    /** Every confirmation among [pairTokens] dated on or after [since]: withdrawn and voided ones count. */
    fun Connection.countGivenSince(
        pairTokens: Collection<String>,
        since: Instant,
    ): Int {
        if (pairTokens.isEmpty()) return 0
        return prepareStatement("SELECT count(*) FROM vouches WHERE pair_token = ANY(?) AND created_at >= ?").use { statement ->
            statement.setArray(1, createArrayOf("text", pairTokens.toTypedArray()))
            statement.setTimestamp(2, Timestamp.from(since))
            statement.executeQuery().use { rs ->
                rs.next()
                rs.getInt(1)
            }
        }
    }

    /**
     * Gives (or gives again) one confirmation. A pair is one row forever: an expired row, or one taken
     * back long enough ago (the caller checks the 180-day wait), is restored in place with a new date;
     * a standing row is left exactly as it is; an admin void stays; the first-given day never moves.
     */
    fun Connection.upsertVouch(
        pairToken: String,
        subjectToken: String,
        partnerToken: String?,
        linkToken: String?,
        day: Instant,
        expiryCutoff: Instant,
    ) {
        prepareStatement(
            """
            INSERT INTO vouches (
                id, pair_token, subject_token, partner_token, link_token, first_given_at, created_at, key_version
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (pair_token) DO UPDATE
            SET created_at = EXCLUDED.created_at,
                withdrawn_at = NULL,
                partner_token = EXCLUDED.partner_token,
                link_token = EXCLUDED.link_token
            WHERE vouches.withdrawn_at IS NOT NULL
               OR vouches.created_at <= ?
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, UUID.randomUUID())
            statement.setString(2, pairToken)
            statement.setString(3, subjectToken)
            statement.setString(4, partnerToken)
            statement.setString(5, linkToken)
            statement.setTimestamp(6, Timestamp.from(day))
            statement.setTimestamp(7, Timestamp.from(day))
            statement.setShort(8, MetInPersonTokens.KEY_VERSION)
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
        partnerTokenOfSubject: String,
        day: Instant,
    ): Int =
        prepareStatement(
            """
            UPDATE vouches
            SET withdrawn_at = ?
            WHERE pair_token = ?
              AND partner_token = ?
              AND withdrawn_at IS NULL
            """.trimIndent(),
        ).use { statement ->
            statement.setTimestamp(1, Timestamp.from(day))
            statement.setString(2, partnerPairToken)
            statement.setString(3, partnerTokenOfSubject)
            statement.executeUpdate()
        }

    private fun ResultSet.toPairRow(): MetInPersonPairRow =
        MetInPersonPairRow(
            pairToken = getString("pair_token"),
            createdAt = getTimestamp("created_at").toInstant(),
            withdrawnAt = getTimestamp("withdrawn_at")?.toInstant(),
            voidedAt = getTimestamp("voided_at")?.toInstant(),
            partnerToken = getString("partner_token"),
            linkToken = getString("link_token"),
        )
}
