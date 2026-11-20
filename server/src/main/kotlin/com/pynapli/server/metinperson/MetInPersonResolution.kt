package com.pynapli.server.metinperson

import java.sql.Connection
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/** A stored confirmation as the batch and the admin view see it (voucher still a token). */
internal data class StoredVouch(
    val id: UUID,
    val subjectUserId: UUID,
    val voucherToken: String,
    val metPartnerUserId: UUID?,
    val linkId: UUID?,
    val createdAt: Instant,
    val withdrawnAt: Instant?,
    val voidedAt: Instant?,
) {
    fun live(now: Instant): Boolean = withdrawnAt == null && voidedAt == null && createdAt.isAfter(MetInPersonRules.expiryCutoff(now))
}

/**
 * Turning tokens back into people needs K3 and a candidate set: a voucher can only have confirmed
 * someone they share a conversation with, so the candidates for a subject are that subject's
 * conversation partners (any conversation, left or not). Each candidate's voucher token is recomputed
 * and matched. A token that matches nobody (the voucher's conversations are gone) resolves to nothing
 * and never counts.
 */
internal object MetInPersonResolution {
    private const val STORED_COLUMNS =
        "id, subject_user_id, voucher_token, met_partner_user_id, link_id, created_at, withdrawn_at, voided_at"

    fun Connection.liveVouches(now: Instant): List<StoredVouch> =
        prepareStatement(
            """
            SELECT $STORED_COLUMNS
            FROM vouches
            WHERE withdrawn_at IS NULL
              AND voided_at IS NULL
              AND created_at > ?
            """.trimIndent(),
        ).use { statement ->
            statement.setTimestamp(1, Timestamp.from(MetInPersonRules.expiryCutoff(now)))
            statement.executeQuery().use { rs -> rs.readStored() }
        }

    fun Connection.vouchesForSubject(subjectUserId: UUID): List<StoredVouch> =
        prepareStatement("SELECT $STORED_COLUMNS FROM vouches WHERE subject_user_id = ? ORDER BY created_at, id").use { statement ->
            statement.setObject(1, subjectUserId)
            statement.executeQuery().use { rs -> rs.readStored() }
        }

    /** Conversation partners of each subject: the only people who could have confirmed them. */
    fun Connection.conversationPartners(subjectUserIds: Collection<UUID>): Map<UUID, Set<UUID>> {
        if (subjectUserIds.isEmpty()) return emptyMap()
        return prepareStatement(
            """
            SELECT DISTINCT a.user_id AS subject_user_id, b.user_id AS candidate_user_id
            FROM conversation_participants a
            JOIN conversation_participants b
              ON b.conversation_id = a.conversation_id
             AND b.user_id <> a.user_id
            WHERE a.user_id = ANY(?)
            """.trimIndent(),
        ).use { statement ->
            statement.setArray(1, createArrayOf("uuid", subjectUserIds.toTypedArray()))
            statement.executeQuery().use { rs ->
                val out = mutableMapOf<UUID, MutableSet<UUID>>()
                while (rs.next()) {
                    out
                        .getOrPut(rs.getObject("subject_user_id", UUID::class.java)) { mutableSetOf() }
                        .add(rs.getObject("candidate_user_id", UUID::class.java))
                }
                out
            }
        }
    }

    /** Vouch id -> voucher user id, for the vouches whose token matches one of the subject's partners. */
    fun Connection.resolveVouchers(
        vouches: List<StoredVouch>,
        tokens: MetInPersonTokens,
    ): Map<UUID, UUID> {
        val partners = conversationPartners(vouches.map { it.subjectUserId }.toSet())
        val tokenCache = mutableMapOf<UUID, String>()
        val resolved = mutableMapOf<UUID, UUID>()
        vouches.groupBy { it.subjectUserId }.forEach { (subjectUserId, rows) ->
            val byToken =
                partners[subjectUserId]
                    .orEmpty()
                    .associateBy { candidate -> tokenCache.getOrPut(candidate) { tokens.voucher(candidate) } }
            rows.forEach { row -> byToken[row.voucherToken]?.let { resolved[row.id] = it } }
        }
        return resolved
    }

    /**
     * The confirmations that count right now, among [resolved] (vouch id -> voucher id) live rows.
     * Applied at batch time, never at write time, so a confirmation that can never count looks the
     * same to the voucher as one that does: the voucher and the subject are both photo-verified and
     * active, the voucher is not quarantined or terminated, and the two are not blocked either way,
     * not (and never were) linked, and do not share a device.
     *
     * The statement starts with a `resolved` CTE bound from two uuid arrays (vouch ids, voucher ids).
     */
    val RESOLVED_AND_ELIGIBLE_CTES =
        """
        resolved AS (
            SELECT v.id, v.subject_user_id, r.voucher_user_id, v.met_partner_user_id, v.link_id
            FROM unnest(?::uuid[], ?::uuid[]) AS r(vouch_id, voucher_user_id)
            JOIN vouches v ON v.id = r.vouch_id
        ),
        eligible AS (
            SELECT r.id, r.subject_user_id, r.voucher_user_id, r.met_partner_user_id, r.link_id
            FROM resolved r
            JOIN users vu ON vu.id = r.voucher_user_id
            JOIN profiles vp ON vp.user_id = r.voucher_user_id
            LEFT JOIN account_risk_states vr ON vr.user_id = r.voucher_user_id
            JOIN users su ON su.id = r.subject_user_id
            JOIN profiles sp ON sp.user_id = r.subject_user_id
            WHERE vu.status = 'active'
              AND vu.deleted_at IS NULL
              AND vp.verified_at IS NOT NULL
              AND COALESCE(vr.tier, 'clean') NOT IN ('quarantine', 'terminate')
              AND su.status = 'active'
              AND su.deleted_at IS NULL
              AND sp.verified_at IS NOT NULL
              AND r.voucher_user_id <> r.subject_user_id
              AND NOT EXISTS (
                  SELECT 1
                  FROM user_blocks b
                  WHERE (b.blocker_user_id = r.voucher_user_id AND b.blocked_user_id = r.subject_user_id)
                     OR (b.blocker_user_id = r.subject_user_id AND b.blocked_user_id = r.voucher_user_id)
              )
              AND NOT EXISTS (
                  SELECT 1
                  FROM user_profile_links l
                  WHERE (l.user_id = r.voucher_user_id AND l.linked_user_id = r.subject_user_id)
                     OR (l.user_id = r.subject_user_id AND l.linked_user_id = r.voucher_user_id)
              )
              AND NOT EXISTS (
                  SELECT 1
                  FROM account_device_links d1
                  JOIN account_device_links d2 ON d2.device_hash = d1.device_hash
                  WHERE d1.user_id = r.voucher_user_id
                    AND d2.user_id = r.subject_user_id
              )
        ),
        couple AS (
            -- A couple credit: the same voucher confirmed both partners together, under the same link,
            -- and that link is still active between them.
            SELECT a.subject_user_id, a.link_id, count(DISTINCT a.voucher_user_id) AS n
            FROM eligible a
            JOIN eligible b
              ON b.voucher_user_id = a.voucher_user_id
             AND b.subject_user_id = a.met_partner_user_id
             AND b.met_partner_user_id = a.subject_user_id
             AND b.link_id = a.link_id
            JOIN user_profile_links l
              ON l.id = a.link_id
             AND l.status = 'active'
             AND l.unlinked_at IS NULL
             AND (
                 (l.user_id = a.subject_user_id AND l.linked_user_id = a.met_partner_user_id)
                 OR (l.user_id = a.met_partner_user_id AND l.linked_user_id = a.subject_user_id)
             )
            WHERE a.link_id IS NOT NULL
            GROUP BY a.subject_user_id, a.link_id
        )
        """.trimIndent()

    fun Connection.bindResolved(
        statement: java.sql.PreparedStatement,
        resolved: Map<UUID, UUID>,
    ) {
        val entries = resolved.entries.toList()
        statement.setArray(1, createArrayOf("uuid", entries.map { it.key }.toTypedArray()))
        statement.setArray(2, createArrayOf("uuid", entries.map { it.value }.toTypedArray()))
    }

    private fun java.sql.ResultSet.readStored(): List<StoredVouch> =
        buildList {
            while (next()) {
                add(
                    StoredVouch(
                        id = getObject("id", UUID::class.java),
                        subjectUserId = getObject("subject_user_id", UUID::class.java),
                        voucherToken = getString("voucher_token"),
                        metPartnerUserId = getObject("met_partner_user_id", UUID::class.java),
                        linkId = getObject("link_id", UUID::class.java),
                        createdAt = getTimestamp("created_at").toInstant(),
                        withdrawnAt = getTimestamp("withdrawn_at")?.toInstant(),
                        voidedAt = getTimestamp("voided_at")?.toInstant(),
                    ),
                )
            }
        }
}
