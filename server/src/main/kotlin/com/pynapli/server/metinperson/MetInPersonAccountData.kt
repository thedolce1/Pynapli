package com.pynapli.server.metinperson

import com.pynapli.server.metinperson.JdbcMetInPersonSql.conversationPartners
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.sql.Connection
import java.util.UUID

/**
 * Account deletion and data export for "Met in person". Rows hold only tokens, so the person's rows are
 * found by recomputing them: what they gave by their pair tokens over everyone they shared a
 * conversation with (participant rows are never deleted), what they received by their subject token.
 * Deletion anonymises the user row instead of deleting it, so these statements run inside the deletion
 * transaction.
 */
class MetInPersonAccountData(
    private val tokens: MetInPersonTokens,
) {
    fun erase(
        connection: Connection,
        userId: UUID,
    ) {
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
     */
    fun exportSection(
        connection: Connection,
        userId: UUID,
    ): JsonObject {
        val partners = connection.conversationPartners(userId).toList()
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
                                        put("subjectDisplayName", names[subject] ?: "Pynapli User")
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
                    """
                    SELECT COALESCE(vb.person_badge AND vb.frozen_at IS NULL, false) AS person_badge,
                           COALESCE(vb.couple_badge AND vb.frozen_at IS NULL, false) AS couple_badge,
                           COALESCE(ds.show_met_in_person_badge, false) AS show_badge
                    FROM users u
                    LEFT JOIN vouch_badges vb ON vb.subject_user_id = u.id
                    LEFT JOIN discovery_settings ds ON ds.user_id = u.id
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

    private fun Connection.displayNames(userIds: Set<UUID>): Map<UUID, String> {
        if (userIds.isEmpty()) return emptyMap()
        return prepareStatement(
            "SELECT user_id, COALESCE(NULLIF(display_name, ''), 'Pynapli User') AS name FROM profiles WHERE user_id = ANY(?)",
        ).use { statement ->
            statement.setArray(1, createArrayOf("uuid", userIds.toTypedArray()))
            statement.executeQuery().use { rs ->
                buildMap { while (rs.next()) put(rs.getObject("user_id", UUID::class.java), rs.getString("name")) }
            }
        }
    }
}
