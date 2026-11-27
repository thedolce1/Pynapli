package com.pynapli.server.metinperson

import com.pynapli.server.metinperson.JdbcMetInPersonSql.conversationPartners
import com.pynapli.server.metinperson.JdbcMetInPersonSql.conversationPartnersVisibleTo
import com.pynapli.server.metinperson.JdbcMetInPersonSql.keyMatches
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.time.Instant
import java.util.UUID

/**
 * Account deletion and data export for "Met in person". Rows hold only tokens, so the person's rows are
 * found by recomputing them: what they gave by their pair tokens over everyone they shared a
 * conversation with (participant rows are never deleted), what they received by their subject token.
 * Deletion anonymises the user row instead of deleting it, so these statements run inside the deletion
 * transaction.
 *
 * Both refuse loudly when the configured key does not match the stored check value: with the wrong key
 * the recomputed tokens match nothing, so a deletion would silently leave the person's confirmations
 * behind and an export would silently omit them. The exception rolls the deletion back (the person can
 * retry once the key is fixed) and the error log is the alert.
 */
class MetInPersonAccountData(
    private val tokens: MetInPersonTokens,
) {
    companion object {
        private val logger = LoggerFactory.getLogger(MetInPersonAccountData::class.java)

        /** With the local development key (tests and local runs). */
        fun localDevelopment(): MetInPersonAccountData = MetInPersonAccountData(MetInPersonTokens.localDevelopment())
    }

    fun erase(
        connection: Connection,
        userId: UUID,
    ) {
        connection.requireKey("account deletion")
        val given = connection.conversationPartners(userId).map { tokens.pair(userId, it) }
        if (given.isNotEmpty()) {
            connection.prepareStatement("DELETE FROM vouches WHERE pair_token = ANY(?)").use { statement ->
                statement.setArray(1, connection.createArrayOf("text", given.toTypedArray()))
                statement.executeUpdate()
            }
        }
        connection.prepareStatement("DELETE FROM vouches WHERE subject_token = ?").use { statement ->
            statement.setString(1, tokens.subject(userId))
            statement.executeUpdate()
        }
        // Their partner keeps a personal confirmation; the couple credit with them ends.
        connection.prepareStatement("UPDATE vouches SET partner_token = NULL, link_token = NULL WHERE partner_token = ?").use { statement ->
            statement.setString(1, tokens.partner(userId))
            statement.executeUpdate()
        }
        connection.prepareStatement("DELETE FROM vouch_badge_states WHERE subject_token = ?").use { statement ->
            statement.setString(1, tokens.subject(userId))
            statement.executeUpdate()
        }
        connection.prepareStatement("DELETE FROM vouch_badges WHERE subject_user_id = ?").use { statement ->
            statement.setObject(1, userId)
            statement.executeUpdate()
        }
        connection
            .prepareStatement(
                """
                UPDATE vouch_badges
                SET couple_badge = false, couple_link_id = NULL
                WHERE couple_link_id IN (SELECT id FROM user_profile_links WHERE user_id = ? OR linked_user_id = ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, userId)
                statement.setObject(2, userId)
                statement.executeUpdate()
            }
    }

    /**
     * The person's own part: the confirmations they gave (the subject's display name at export time and
     * the day), and, as a subject, only their published badge state and setting, exactly what everyone
     * else sees, at the same moment. Never who confirmed them, never a count, never anything pending.
     * Confirmations of someone now hiding from the person ("Hide from people you know") are left out,
     * as the export's other viewer-filtered sections leave out people hiding from them:
     * [viewerContactToken] is the person's contact block target token.
     */
    fun exportSection(
        connection: Connection,
        userId: UUID,
        viewerContactToken: String,
    ): JsonObject {
        connection.requireKey("data export")
        val partners = connection.conversationPartnersVisibleTo(userId, viewerContactToken).toList()
        val subjectByToken = partners.associateBy { tokens.pair(userId, it) }
        val given =
            if (subjectByToken.isEmpty()) {
                JsonArray(emptyList())
            } else {
                connection
                    .prepareStatement(
                        "SELECT pair_token, created_at, withdrawn_at, partner_token FROM vouches WHERE pair_token = ANY(?) ORDER BY created_at",
                    ).use { statement ->
                        statement.setArray(1, connection.createArrayOf("text", subjectByToken.keys.toTypedArray()))
                        statement.executeQuery().use { rs ->
                            val rows = mutableListOf<Triple<UUID, String, Pair<String?, Boolean>>>()
                            while (rs.next()) {
                                val subject = subjectByToken[rs.getString("pair_token")] ?: continue
                                rows +=
                                    Triple(
                                        subject,
                                        rs.getTimestamp("created_at").toInstant().toString(),
                                        rs.getTimestamp("withdrawn_at")?.toInstant()?.toString() to (rs.getString("partner_token") != null),
                                    )
                            }
                            val names = connection.displayNames(rows.map { it.first }.toSet())
                            JsonArray(
                                rows.map { (subject, confirmedOn, rest) ->
                                    buildJsonObject {
                                        put("subjectDisplayName", names[subject] ?: "New member")
                                        put("confirmedOn", confirmedOn)
                                        put("withdrawnOn", rest.first)
                                        put("withPartner", rest.second)
                                    }
                                },
                            )
                        }
                    }
            }
        val badge =
            connection
                .prepareStatement(
                    // The public profile's display rules (JdbcProfileSql.PUBLIC_PROFILE_QUERY), so the export
                    // says exactly what anyone sees: the person's opt-in, verification and risk tier; for the
                    // couple badge also the active link row, the partner's own published couple badge on the
                    // same link and opt-in, and both published after the link's last change.
                    """
                    SELECT COALESCE(
                               vb.person_badge
                               AND vb.frozen_at IS NULL
                               AND ds.show_met_in_person_badge
                               AND p.verified_at IS NOT NULL
                               AND COALESCE(r.tier, 'clean') NOT IN ('quarantine', 'terminate'),
                               false
                           ) AS person_badge,
                           COALESCE(
                               vb.couple_badge
                               AND vb.frozen_at IS NULL
                               AND ds.show_met_in_person_badge
                               AND COALESCE(r.tier, 'clean') NOT IN ('quarantine', 'terminate')
                               AND vb.couple_since IS NOT NULL
                               AND EXISTS (
                                   SELECT 1
                                   FROM vouch_badges pvb
                                   JOIN discovery_settings pds ON pds.user_id = pvb.subject_user_id
                                   JOIN user_profile_links cl ON cl.id = vb.couple_link_id
                                   WHERE pvb.subject_user_id <> u.id
                                     AND pvb.couple_badge
                                     AND pvb.frozen_at IS NULL
                                     AND pvb.couple_link_id = vb.couple_link_id
                                     AND pvb.couple_since IS NOT NULL
                                     AND pds.show_met_in_person_badge
                                     AND cl.status = 'active'
                                     AND cl.unlinked_at IS NULL
                                     AND (
                                         (cl.user_id = u.id AND cl.linked_user_id = pvb.subject_user_id)
                                         OR (cl.user_id = pvb.subject_user_id AND cl.linked_user_id = u.id)
                                     )
                                     AND LEAST(vb.couple_since, pvb.couple_since) >= (
                                         SELECT max(lu.updated_at)
                                         FROM user_profile_links lu
                                         WHERE (lu.user_id = u.id AND lu.linked_user_id = pvb.subject_user_id)
                                            OR (lu.user_id = pvb.subject_user_id AND lu.linked_user_id = u.id)
                                     )
                               ),
                               false
                           ) AS couple_badge,
                           COALESCE(ds.show_met_in_person_badge, false) AS show_badge
                    FROM users u
                    JOIN profiles p ON p.user_id = u.id
                    LEFT JOIN vouch_badges vb ON vb.subject_user_id = u.id
                    LEFT JOIN discovery_settings ds ON ds.user_id = u.id
                    LEFT JOIN account_risk_states r ON r.user_id = u.id
                    WHERE u.id = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, userId)
                    statement.executeQuery().use { rs ->
                        buildJsonObject {
                            if (rs.next()) {
                                put("metInPersonBadge", rs.getBoolean("person_badge"))
                                put("metInPersonAsCoupleBadge", rs.getBoolean("couple_badge"))
                                put("showMetInPersonBadge", rs.getBoolean("show_badge"))
                            }
                        }
                    }
                }
        return buildJsonObject {
            put("confirmationsYouGave", given)
            put("yourBadge", badge)
        }
    }

    private fun Connection.requireKey(operation: String) {
        if (keyMatches(tokens, Instant.now())) return
        logger.error("Met in person key check failed during {}; refusing instead of leaving confirmations behind", operation)
        throw IllegalStateException("MET_IN_PERSON_KEY_MISMATCH")
    }

    private fun Connection.displayNames(userIds: Set<UUID>): Map<UUID, String> {
        if (userIds.isEmpty()) return emptyMap()
        return prepareStatement(
            "SELECT user_id, COALESCE(NULLIF(display_name, ''), 'New member') AS name FROM profiles WHERE user_id = ANY(?)",
        ).use { statement ->
            statement.setArray(1, createArrayOf("uuid", userIds.toTypedArray()))
            statement.executeQuery().use { rs ->
                buildMap { while (rs.next()) put(rs.getObject("user_id", UUID::class.java), rs.getString("name")) }
            }
        }
    }
}
