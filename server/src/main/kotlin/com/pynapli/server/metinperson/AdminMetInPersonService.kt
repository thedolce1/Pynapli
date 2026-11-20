package com.pynapli.server.metinperson

import com.pynapli.server.db.inTransaction
import com.pynapli.server.db.withConnection
import com.pynapli.server.metinperson.MetInPersonResolution.RESOLVED_AND_ELIGIBLE_CTES
import com.pynapli.server.metinperson.MetInPersonResolution.bindResolved
import com.pynapli.server.metinperson.MetInPersonResolution.resolveVouchers
import com.pynapli.server.metinperson.MetInPersonResolution.vouchesForSubject
import com.pynapli.server.moderation.ModerationException
import com.pynapli.server.moderation.insertAdminAuditLog
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.sql.Connection
import java.sql.Timestamp
import java.time.Clock
import java.util.UUID
import javax.sql.DataSource

/**
 * Moderation for "Met in person": one read view and three actions (void one confirmation, void
 * everything one voucher gave, freeze a subject's badge). Every call is audited in the same
 * transaction. Audit rows name the subject and counts; a voucher appears only as their K3 token, so
 * the audit log does not become a clear list of who met whom.
 */
class AdminMetInPersonService(
    private val dataSource: DataSource,
    private val tokens: MetInPersonTokens,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun view(
        actorId: String,
        subjectUserId: UUID,
        reason: String,
    ): AdminMetInPersonSubjectDto =
        dataSource.withConnection { connection ->
            connection.inTransaction {
                val now = clock.instant()
                val settings = subjectSettings(subjectUserId) ?: throw ModerationException("USER_NOT_FOUND")
                val rows = vouchesForSubject(subjectUserId)
                val resolved = resolveVouchers(rows, tokens)
                val live = rows.filter { it.live(now) && it.id in resolved }
                val qualifying = qualifying(resolved.filterKeys { id -> live.any { it.id == id } })
                val vouchers = resolved.values.toSet()
                val edges = liveEdges(setOf(subjectUserId) + vouchers)
                val sharedDevice = sharedDeviceWith(subjectUserId, vouchers)
                val names = displayNames(vouchers)
                val badge = badgeRow(subjectUserId)
                insertAdminAuditLog(
                    actorId = actorId,
                    action = "met_in_person.view",
                    targetType = "user",
                    targetId = subjectUserId,
                    reason = reason,
                    metadataJson =
                        buildJsonObject {
                            put("confirmations", rows.size)
                            put("qualifyingPerson", qualifying.person.size)
                        }.toString(),
                )
                AdminMetInPersonSubjectDto(
                    subjectUserId = subjectUserId.toString(),
                    personBadge = badge?.personBadge == true,
                    coupleBadge = badge?.coupleBadge == true,
                    frozen = badge?.frozen == true,
                    showBadgeSetting = settings,
                    qualifyingPersonCount = qualifying.person.size,
                    qualifyingCoupleCount = qualifying.coupleVouchers.size,
                    confirmations =
                        rows.map { row ->
                            val voucher = resolved[row.id]
                            AdminMetInPersonConfirmationDto(
                                confirmationId = row.id.toString(),
                                voucherUserId = voucher?.toString(),
                                voucherDisplayName = voucher?.let { names[it] },
                                givenOn = row.createdAt.toString(),
                                withdrawnOn = row.withdrawnAt?.toString(),
                                voidedOn = row.voidedAt?.toString(),
                                withPartnerUserId = row.metPartnerUserId?.toString(),
                                coupleLinkActive = row.linkId != null,
                                live = row.live(now),
                                counts = row.id in qualifying.person,
                                reciprocal = voucher != null && (subjectUserId to voucher) in edges,
                                closedGroup =
                                    voucher != null &&
                                        vouchers.any { other ->
                                            other != voucher && ((voucher to other) in edges || (other to voucher) in edges)
                                        },
                                sharedDevice = voucher != null && voucher in sharedDevice,
                            )
                        },
                )
            }
        }

    suspend fun voidConfirmation(
        actorId: String,
        confirmationId: UUID,
        reason: String,
    ): AdminMetInPersonActionResultDto =
        dataSource.withConnection { connection ->
            connection.inTransaction {
                val subjectUserId =
                    prepareStatement(
                        "UPDATE vouches SET voided_at = COALESCE(voided_at, ?) WHERE id = ? RETURNING subject_user_id",
                    ).use { statement ->
                        statement.setTimestamp(1, Timestamp.from(clock.instant()))
                        statement.setObject(2, confirmationId)
                        statement.executeQuery().use { rs ->
                            if (!rs.next()) throw ModerationException("MET_IN_PERSON_CONFIRMATION_NOT_FOUND")
                            rs.getObject("subject_user_id", UUID::class.java)
                        }
                    }
                insertAdminAuditLog(
                    actorId = actorId,
                    action = "met_in_person.void_confirmation",
                    targetType = "met_in_person_confirmation",
                    targetId = confirmationId,
                    reason = reason,
                    metadataJson = buildJsonObject { put("subjectUserId", subjectUserId.toString()) }.toString(),
                )
                AdminMetInPersonActionResultDto(affected = 1)
            }
        }

    /** The voucher id arrives in the body, so the request-level audit (which stores the path) never holds it. */
    suspend fun voidAllFromVoucher(
        actorId: String,
        voucherUserId: UUID,
        reason: String,
    ): AdminMetInPersonActionResultDto =
        dataSource.withConnection { connection ->
            connection.inTransaction {
                val voucherToken = tokens.voucher(voucherUserId)
                val affected =
                    prepareStatement(
                        "UPDATE vouches SET voided_at = ? WHERE voucher_token = ? AND voided_at IS NULL",
                    ).use { statement ->
                        statement.setTimestamp(1, Timestamp.from(clock.instant()))
                        statement.setString(2, voucherToken)
                        statement.executeUpdate()
                    }
                insertAdminAuditLog(
                    actorId = actorId,
                    action = "met_in_person.void_voucher",
                    targetType = "met_in_person_voucher",
                    targetId = null,
                    reason = reason,
                    metadataJson =
                        buildJsonObject {
                            put("voucherToken", voucherToken)
                            put("voided", affected)
                        }.toString(),
                )
                AdminMetInPersonActionResultDto(affected = affected)
            }
        }

    /** Freeze hides both badges at once and keeps them off at every batch; unfreeze lets the next batch decide. */
    suspend fun setFrozen(
        actorId: String,
        subjectUserId: UUID,
        frozen: Boolean,
        reason: String,
    ): AdminMetInPersonActionResultDto =
        dataSource.withConnection { connection ->
            connection.inTransaction {
                subjectSettings(subjectUserId) ?: throw ModerationException("USER_NOT_FOUND")
                val now = Timestamp.from(clock.instant())
                prepareStatement(
                    """
                    INSERT INTO vouch_badges (subject_user_id, person_badge, couple_badge, couple_link_id, computed_at, frozen_at)
                    VALUES (?, false, false, NULL, ?, CASE WHEN ? THEN ?::timestamptz END)
                    ON CONFLICT (subject_user_id) DO UPDATE
                    SET frozen_at = EXCLUDED.frozen_at,
                        person_badge = CASE WHEN EXCLUDED.frozen_at IS NULL THEN vouch_badges.person_badge ELSE false END,
                        couple_badge = CASE WHEN EXCLUDED.frozen_at IS NULL THEN vouch_badges.couple_badge ELSE false END
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, subjectUserId)
                    statement.setTimestamp(2, now)
                    statement.setBoolean(3, frozen)
                    statement.setTimestamp(4, now)
                    statement.executeUpdate()
                }
                insertAdminAuditLog(
                    actorId = actorId,
                    action = if (frozen) "met_in_person.freeze" else "met_in_person.unfreeze",
                    targetType = "user",
                    targetId = subjectUserId,
                    reason = reason,
                    metadataJson = "{}",
                )
                AdminMetInPersonActionResultDto(affected = 1)
            }
        }

    private class Qualifying(
        val person: Set<UUID>,
        val coupleVouchers: Set<UUID>,
    )

    /** The same filter the batch applies, for one subject's live rows. */
    private fun Connection.qualifying(resolved: Map<UUID, UUID>): Qualifying {
        if (resolved.isEmpty()) return Qualifying(emptySet(), emptySet())
        return prepareStatement(
            """
            WITH $RESOLVED_AND_ELIGIBLE_CTES
            SELECT e.id,
                   e.voucher_user_id,
                   EXISTS (
                       SELECT 1 FROM couple c
                       WHERE c.subject_user_id = e.subject_user_id
                         AND c.link_id = e.link_id
                   ) AND EXISTS (
                       SELECT 1 FROM eligible b
                       WHERE b.voucher_user_id = e.voucher_user_id
                         AND b.subject_user_id = e.met_partner_user_id
                         AND b.met_partner_user_id = e.subject_user_id
                         AND b.link_id = e.link_id
                   ) AS couple_credit
            FROM eligible e
            """.trimIndent(),
        ).use { statement ->
            bindResolved(statement, resolved)
            statement.executeQuery().use { rs ->
                val person = mutableSetOf<UUID>()
                val couple = mutableSetOf<UUID>()
                while (rs.next()) {
                    person += rs.getObject("id", UUID::class.java)
                    if (rs.getBoolean("couple_credit")) couple += rs.getObject("voucher_user_id", UUID::class.java)
                }
                Qualifying(person, couple)
            }
        }
    }

    /** Directed live confirmations among [people] (voucher, subject), found by recomputing pair tokens. */
    private fun Connection.liveEdges(people: Set<UUID>): Set<Pair<UUID, UUID>> {
        val bounded = people.take(MAX_RING_PEOPLE)
        val byToken =
            buildMap<String, Pair<UUID, UUID>> {
                bounded.forEach { a -> bounded.forEach { b -> if (a != b) put(tokens.pair(a, b), a to b) } }
            }
        if (byToken.isEmpty()) return emptySet()
        return prepareStatement(
            "SELECT pair_token FROM vouches WHERE pair_token = ANY(?) AND withdrawn_at IS NULL AND voided_at IS NULL AND created_at > ?",
        ).use { statement ->
            statement.setArray(1, createArrayOf("text", byToken.keys.toTypedArray()))
            statement.setTimestamp(2, Timestamp.from(MetInPersonRules.expiryCutoff(clock.instant())))
            statement.executeQuery().use { rs ->
                buildSet<Pair<UUID, UUID>> { while (rs.next()) byToken[rs.getString("pair_token")]?.let { add(it) } }
            }
        }
    }

    private fun Connection.sharedDeviceWith(
        subjectUserId: UUID,
        vouchers: Set<UUID>,
    ): Set<UUID> {
        if (vouchers.isEmpty()) return emptySet()
        return prepareStatement(
            """
            SELECT DISTINCT d2.user_id
            FROM account_device_links d1
            JOIN account_device_links d2 ON d2.device_hash = d1.device_hash
            WHERE d1.user_id = ?
              AND d2.user_id = ANY(?)
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, subjectUserId)
            statement.setArray(2, createArrayOf("uuid", vouchers.toTypedArray()))
            statement.executeQuery().use { rs -> buildSet { while (rs.next()) add(rs.getObject(1, UUID::class.java)) } }
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

    /** The subject's opt-in, or null when there is no such user. */
    private fun Connection.subjectSettings(subjectUserId: UUID): Boolean? =
        prepareStatement(
            """
            SELECT COALESCE(ds.show_met_in_person_badge, false) AS show_badge
            FROM users u
            LEFT JOIN discovery_settings ds ON ds.user_id = u.id
            WHERE u.id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, subjectUserId)
            statement.executeQuery().use { rs -> if (rs.next()) rs.getBoolean("show_badge") else null }
        }

    private class BadgeRow(
        val personBadge: Boolean,
        val coupleBadge: Boolean,
        val frozen: Boolean,
    )

    private fun Connection.badgeRow(subjectUserId: UUID): BadgeRow? =
        prepareStatement("SELECT person_badge, couple_badge, frozen_at FROM vouch_badges WHERE subject_user_id = ?").use { statement ->
            statement.setObject(1, subjectUserId)
            statement.executeQuery().use { rs ->
                if (!rs.next()) {
                    null
                } else {
                    BadgeRow(rs.getBoolean("person_badge"), rs.getBoolean("couple_badge"), rs.getTimestamp("frozen_at") != null)
                }
            }
        }

    private companion object {
        /** Ring checks compare every pair among the subject and their vouchers; bound the work. */
        const val MAX_RING_PEOPLE = 60
    }
}

@Serializable
data class AdminMetInPersonSubjectDto(
    val subjectUserId: String,
    val personBadge: Boolean,
    val coupleBadge: Boolean,
    val frozen: Boolean,
    val showBadgeSetting: Boolean,
    // Confirmations that would count if the batch ran now (the stored badge changes only at the batch).
    val qualifyingPersonCount: Int,
    val qualifyingCoupleCount: Int,
    val confirmations: List<AdminMetInPersonConfirmationDto>,
)

@Serializable
data class AdminMetInPersonConfirmationDto(
    val confirmationId: String,
    // Resolved through K3; null when the voucher can no longer be matched (such a row never counts).
    val voucherUserId: String? = null,
    val voucherDisplayName: String? = null,
    val givenOn: String,
    val withdrawnOn: String? = null,
    val voidedOn: String? = null,
    val withPartnerUserId: String? = null,
    val coupleLinkActive: Boolean = false,
    val live: Boolean,
    val counts: Boolean,
    // The subject also confirmed this voucher.
    val reciprocal: Boolean = false,
    // This voucher confirmed, or was confirmed by, another of the subject's vouchers.
    val closedGroup: Boolean = false,
    val sharedDevice: Boolean = false,
)

@Serializable
data class AdminMetInPersonActionResultDto(
    val affected: Int,
)

@Serializable
data class AdminMetInPersonVoidVoucherRequestDto(
    val voucherUserId: String,
)

@Serializable
data class AdminMetInPersonFreezeRequestDto(
    val frozen: Boolean = true,
)
