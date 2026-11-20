package com.pynapli.server.metinperson

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.sql.Connection
import java.util.UUID

/**
 * Account deletion and data export for "Met in person". Deletion anonymises the user row instead of
 * deleting it, so nothing cascades: these statements run inside the deletion transaction.
 */
class MetInPersonAccountData(
    private val tokens: MetInPersonTokens,
) {
    /**
     * Removes the confirmations the person gave (found by their voucher token) and the ones they
     * received with their badge. A partner they were confirmed with keeps their own confirmation as a
     * personal one (couple credit ends with the link, which deletion also ends).
     */
    fun erase(
        connection: Connection,
        userId: UUID,
    ) {
        connection.prepareStatement("DELETE FROM vouches WHERE voucher_token = ?").use { statement ->
            statement.setString(1, tokens.voucher(userId))
            statement.executeUpdate()
        }
        connection.prepareStatement("DELETE FROM vouches WHERE subject_user_id = ?").use { statement ->
            statement.setObject(1, userId)
            statement.executeUpdate()
        }
        connection
            .prepareStatement(
                """
                UPDATE vouches
                SET met_partner_user_id = NULL, link_id = NULL
                WHERE met_partner_user_id = ?
                   OR link_id IN (SELECT id FROM user_profile_links WHERE user_id = ? OR linked_user_id = ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, userId)
                statement.setObject(2, userId)
                statement.setObject(3, userId)
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
     * the day), and, as a subject, only their badge state and setting. Never who confirmed them, and
     * never a count.
     */
    fun exportSection(
        connection: Connection,
        userId: UUID,
    ): JsonObject {
        val given =
            connection
                .prepareStatement(
                    """
                    SELECT COALESCE(NULLIF(p.display_name, ''), 'Pynapli User') AS subject_display_name,
                           v.created_at,
                           v.withdrawn_at,
                           (v.met_partner_user_id IS NOT NULL) AS with_partner
                    FROM vouches v
                    LEFT JOIN profiles p ON p.user_id = v.subject_user_id
                    WHERE v.voucher_token = ?
                    ORDER BY v.created_at
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, tokens.voucher(userId))
                    statement.executeQuery().use { rs ->
                        JsonArray(
                            buildList {
                                while (rs.next()) {
                                    add(
                                        buildJsonObject {
                                            put("subjectDisplayName", rs.getString("subject_display_name"))
                                            put("confirmedOn", rs.getTimestamp("created_at").toInstant().toString())
                                            put("withdrawnOn", rs.getTimestamp("withdrawn_at")?.toInstant()?.toString())
                                            put("withPartner", rs.getBoolean("with_partner"))
                                        },
                                    )
                                }
                            },
                        )
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
}
