package com.pynapli.server.metinperson

import com.pynapli.server.db.inTransaction
import com.pynapli.server.db.withConnection
import com.pynapli.server.metinperson.JdbcMetInPersonSql.conversationPartners
import com.pynapli.server.metinperson.JdbcMetInPersonSql.keyMatches
import com.pynapli.server.metinperson.MetInPersonEvaluator.Companion.storedByPairTokens
import com.pynapli.server.metinperson.MetInPersonEvaluator.Companion.storedBySubjectTokens
import com.pynapli.server.moderation.ModerationException
import com.pynapli.server.moderation.insertAdminAuditLog
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.sql.Connection
import java.sql.Timestamp
import java.time.Clock
import java.util.UUID
import javax.sql.DataSource

/**
 * Moderation for "Met in person": one read view and three actions (void one confirmation, void
 * everything one voucher gave, freeze a subject's badge), each with its undo. Every call is audited in
 * the same transaction. Audit rows never hold a voucher id in clear: they carry `voucherRef`, a K3 token a
 * key holder (senior safety staff with key access) can match by recomputing it over members, so an
 * auditor can tell whose confirmations were voided while lower roles and a database copy cannot.
 */
class AdminMetInPersonService(
    private val dataSource: DataSource,
    private val tokens: MetInPersonTokens,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val evaluator = MetInPersonEvaluator(tokens)

    suspend fun view(
        actorId: String,
        subjectUserId: UUID,
        reason: String,
    ): AdminMetInPersonSubjectDto =
        withKey { now ->
            val settings = subjectSettings(subjectUserId) ?: throw ModerationException("USER_NOT_FOUND")
            val evaluation = evaluator.evaluate(this, listOf(subjectUserId), now)[subjectUserId]
            val verdicts = evaluation?.rows.orEmpty()
            val vouchers = verdicts.mapNotNull { it.voucherUserId }.toSet()
            val edges = liveEdges(setOf(subjectUserId) + vouchers, now)
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
                        put("confirmations", verdicts.size)
                        put("qualifyingPerson", evaluation?.independentVouchers ?: 0)
                    }.toString(),
            )
            AdminMetInPersonSubjectDto(
                subjectUserId = subjectUserId.toString(),
                personBadge = badge?.personBadge == true,
                coupleBadge = badge?.coupleBadge == true,
                frozen = badge?.frozen == true,
                showBadgeSetting = settings,
                qualifyingPersonCount = evaluation?.independentVouchers ?: 0,
                personQualifiesNow = evaluation?.personQualifies == true,
                qualifyingCoupleCount = evaluation?.coupleVouchers?.size ?: 0,
                confirmations =
                    verdicts.map { verdict ->
                        val row = verdict.vouch
                        val voucher = verdict.voucherUserId
                        AdminMetInPersonConfirmationDto(
                            confirmationId = row.id.toString(),
                            voucherUserId = voucher?.toString(),
                            voucherDisplayName = voucher?.let { names[it] },
                            givenOn = row.createdAt.toString(),
                            firstGivenOn = row.firstGivenAt.toString(),
                            withdrawnOn = row.withdrawnAt?.toString(),
                            voidedOn = row.voidedAt?.toString(),
                            withPartner = row.partnerToken != null,
                            coupleLinkActive = row.linkToken != null,
                            live = row.live(now),
                            counts = verdict.counts,
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

    /** Voids (or un-voids) one confirmation of [subjectUserId]; the subject id is checked against its token. */
    suspend fun setConfirmationVoided(
        actorId: String,
        subjectUserId: UUID,
        confirmationId: UUID,
        voided: Boolean,
        reason: String,
    ): AdminMetInPersonActionResultDto =
        withKey { now ->
            val row =
                storedBySubjectTokens(listOf(tokens.subject(subjectUserId)))
                    .firstOrNull { it.id == confirmationId }
                    ?: throw ModerationException("MET_IN_PERSON_CONFIRMATION_NOT_FOUND")
            val voucher = evaluator.evaluate(this, listOf(subjectUserId), now)[subjectUserId]?.rows?.firstOrNull { it.vouch.id == row.id }?.voucherUserId
            val affected = setVoided(listOf(row.pairToken), voided, now)
            insertAdminAuditLog(
                actorId = actorId,
                action = if (voided) "met_in_person.void_confirmation" else "met_in_person.unvoid_confirmation",
                targetType = "met_in_person_confirmation",
                targetId = confirmationId,
                reason = reason,
                metadataJson =
                    buildJsonObject {
                        put("subjectRef", tokens.subject(subjectUserId))
                        put("voucherRef", voucher?.let(tokens::auditVoucher))
                    }.toString(),
            )
            AdminMetInPersonActionResultDto(affected = affected)
        }

    /** Voids (or un-voids) every confirmation [voucherUserId] gave, found through their pair tokens. */
    suspend fun setVoucherVoided(
        actorId: String,
        voucherUserId: UUID,
        voided: Boolean,
        reason: String,
    ): AdminMetInPersonActionResultDto =
        withKey { now ->
            val pairTokens = conversationPartners(voucherUserId).map { tokens.pair(voucherUserId, it) }
            val ids = storedByPairTokens(pairTokens).map { it.id.toString() }
            val affected = setVoided(pairTokens, voided, now)
            insertAdminAuditLog(
                actorId = actorId,
                action = if (voided) "met_in_person.void_voucher" else "met_in_person.unvoid_voucher",
                targetType = "met_in_person_voucher",
                targetId = null,
                reason = reason,
                metadataJson =
                    buildJsonObject {
                        put("voucherRef", tokens.auditVoucher(voucherUserId))
                        put("confirmations", JsonArray(ids.map { JsonPrimitive(it) }))
                        put("affected", affected)
                    }.toString(),
            )
            AdminMetInPersonActionResultDto(affected = affected)
        }

    /** Freeze hides both badges at once and keeps them off at every batch; unfreeze lets later batches decide. */
    suspend fun setFrozen(
        actorId: String,
        subjectUserId: UUID,
        frozen: Boolean,
        reason: String,
    ): AdminMetInPersonActionResultDto =
        withKey { now ->
            subjectSettings(subjectUserId) ?: throw ModerationException("USER_NOT_FOUND")
            val at = Timestamp.from(now)
            prepareStatement(
                """
                INSERT INTO vouch_badges (subject_user_id, person_badge, couple_badge, couple_link_id, updated_at, frozen_at)
                VALUES (?, false, false, NULL, ?, CASE WHEN ? THEN ?::timestamptz END)
                ON CONFLICT (subject_user_id) DO UPDATE
                SET frozen_at = EXCLUDED.frozen_at,
                    person_badge = CASE WHEN EXCLUDED.frozen_at IS NULL THEN vouch_badges.person_badge ELSE false END,
                    couple_badge = CASE WHEN EXCLUDED.frozen_at IS NULL THEN vouch_badges.couple_badge ELSE false END,
                    updated_at = EXCLUDED.updated_at
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, subjectUserId)
                statement.setTimestamp(2, at)
                statement.setBoolean(3, frozen)
                statement.setTimestamp(4, at)
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

    private suspend fun <T> withKey(block: Connection.(java.time.Instant) -> T): T =
        dataSource.withConnection { connection ->
            connection.inTransaction {
                val now = clock.instant()
                if (!keyMatches(tokens, now)) throw ModerationException("MET_IN_PERSON_KEY_MISMATCH")
                block(now)
            }
        }

    private fun Connection.setVoided(
        pairTokens: List<String>,
        voided: Boolean,
        now: java.time.Instant,
    ): Int {
        if (pairTokens.isEmpty()) return 0
        val sql =
            if (voided) {
                "UPDATE vouches SET voided_at = ? WHERE pair_token = ANY(?) AND voided_at IS NULL"
            } else {
                "UPDATE vouches SET voided_at = NULL WHERE ?::timestamptz IS NOT NULL AND pair_token = ANY(?) AND voided_at IS NOT NULL"
            }
        return prepareStatement(sql).use { statement ->
            statement.setTimestamp(1, Timestamp.from(now))
            statement.setArray(2, createArrayOf("text", pairTokens.toTypedArray()))
            statement.executeUpdate()
        }
    }

    /** Directed live confirmations among [people] (voucher, subject), found by recomputing pair tokens. */
    private fun Connection.liveEdges(
        people: Set<UUID>,
        now: java.time.Instant,
    ): Set<Pair<UUID, UUID>> {
        val bounded = people.take(MAX_RING_PEOPLE)
        val byToken =
            buildMap<String, Pair<UUID, UUID>> {
                bounded.forEach { a -> bounded.forEach { b -> if (a != b) put(tokens.pair(a, b), a to b) } }
            }
        return storedByPairTokens(byToken.keys).filter { it.live(now) }.mapNotNull { byToken[it.pairToken] }.toSet()
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
    // Independent qualifying vouchers if the batch ran now (published badges change only at batches,
    // after the hysteresis delay).
    val qualifyingPersonCount: Int,
    val personQualifiesNow: Boolean,
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
    val firstGivenOn: String,
    val withdrawnOn: String? = null,
    val voidedOn: String? = null,
    val withPartner: Boolean = false,
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
data class AdminMetInPersonVoidRequestDto(
    val subjectUserId: String,
    val voided: Boolean = true,
)

@Serializable
data class AdminMetInPersonVoidVoucherRequestDto(
    val voucherUserId: String,
    val voided: Boolean = true,
)

@Serializable
data class AdminMetInPersonFreezeRequestDto(
    val frozen: Boolean = true,
)
