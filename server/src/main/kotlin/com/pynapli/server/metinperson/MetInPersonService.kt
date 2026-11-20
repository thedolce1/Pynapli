package com.pynapli.server.metinperson

import com.pynapli.server.db.inTransaction
import com.pynapli.server.db.lockForTransaction
import com.pynapli.server.db.withConnection
import com.pynapli.server.messaging.MessagingException
import com.pynapli.server.messaging.MetInPersonOfferSource
import com.pynapli.server.metinperson.JdbcMetInPersonSql.countGivenSince
import com.pynapli.server.metinperson.JdbcMetInPersonSql.pairRows
import com.pynapli.server.metinperson.JdbcMetInPersonSql.subjectFacts
import com.pynapli.server.metinperson.JdbcMetInPersonSql.upsertVouch
import com.pynapli.server.metinperson.JdbcMetInPersonSql.viewerFacts
import com.pynapli.server.metinperson.JdbcMetInPersonSql.withdrawPartnerVouch
import com.pynapli.server.metinperson.JdbcMetInPersonSql.withdrawVouch
import com.pynapli.shared.network.dto.ConfirmMetInPersonRequestDto
import com.pynapli.shared.network.dto.MetInPersonOfferDto
import com.pynapli.shared.network.dto.WithdrawMetInPersonRequestDto
import java.sql.Connection
import java.time.Clock
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * The voucher's side of "Met in person" (TRUST_AND_SAFETY_SPEC "Met in person").
 *
 * Privacy properties this class keeps:
 * - It has no notifier, broadcaster, Activity or outbox dependency at all: giving, withdrawing and the
 *   read-mark offer cannot reach the subject or anyone else.
 * - Every response is computed from the viewer's own rows only. Nothing in it depends on other people's
 *   confirmations, a count, or a badge, so completing a threshold looks exactly like not completing it.
 * - Every refusal is the one code `MET_IN_PERSON_NOT_AVAILABLE`; withdraw never refuses once the
 *   conversation is the viewer's.
 * - The voucher id is never stored or logged: only the K3 tokens.
 */
class MetInPersonService(
    private val dataSource: DataSource,
    private val tokens: MetInPersonTokens,
    private val clock: Clock = Clock.systemUTC(),
) : MetInPersonOfferSource {
    override suspend fun offersFor(
        viewerUserId: UUID,
        conversationId: UUID,
    ): List<MetInPersonOfferDto> =
        dataSource.withConnection { connection ->
            val now = clock.instant()
            val snapshot = connection.snapshot(viewerUserId, conversationId, now) ?: return@withConnection emptyList()
            snapshot.states.filter(MetInPersonRules::actionable)
        }

    suspend fun confirm(
        viewerUserId: UUID,
        conversationIdText: String,
        request: ConfirmMetInPersonRequestDto,
    ): MetInPersonOfferDto {
        val conversationId = conversationIdText.toUuidOrRefuse()
        val subjectUserId = request.subjectUserId.toUuidOrRefuse()
        return dataSource.withConnection { connection ->
            connection.inTransaction {
                val now = clock.instant()
                val voucherToken = tokens.voucher(viewerUserId)
                // Serialises one voucher's writes so the monthly cap cannot be raced past.
                lockForTransaction(LOCK_NAMESPACE, voucherToken)
                val snapshot = snapshot(viewerUserId, conversationId, now) ?: refuse()
                val state = snapshot.stateFor(subjectUserId) ?: refuse()
                if (state.given) {
                    // A repeat (or a retry after a lost response) changes nothing, not even the date.
                    return@inTransaction state
                }
                if (!state.canConfirm) refuse()
                val partner = state.partner
                if (request.includePartner && partner == null) refuse()
                val subject = snapshot.facts.getValue(subjectUserId)
                val partnerUserId = if (request.includePartner) UUID.fromString(partner!!.partnerUserId) else null
                val linkId = if (partnerUserId != null) subject.partnerLinkId else null
                val day = MetInPersonRules.day(now)
                val conversationToken = tokens.conversation(conversationId)
                upsertVouch(
                    subjectUserId = subjectUserId,
                    voucherToken = voucherToken,
                    pairToken = tokens.pair(viewerUserId, subjectUserId),
                    metPartnerUserId = partnerUserId,
                    linkId = linkId,
                    conversationToken = conversationToken,
                    day = day,
                    expiryCutoff = MetInPersonRules.expiryCutoff(now),
                )
                if (partnerUserId != null) {
                    upsertVouch(
                        subjectUserId = partnerUserId,
                        voucherToken = voucherToken,
                        pairToken = tokens.pair(viewerUserId, partnerUserId),
                        metPartnerUserId = subjectUserId,
                        linkId = linkId,
                        conversationToken = conversationToken,
                        day = day,
                        expiryCutoff = MetInPersonRules.expiryCutoff(now),
                    )
                }
                snapshot(viewerUserId, conversationId, now)?.stateFor(subjectUserId) ?: refuse()
            }
        }
    }

    suspend fun withdraw(
        viewerUserId: UUID,
        conversationIdText: String,
        request: WithdrawMetInPersonRequestDto,
    ): MetInPersonOfferDto {
        val conversationId = conversationIdText.toUuidOrRefuse()
        val subjectUserId = request.subjectUserId.toUuidOrRefuse()
        return dataSource.withConnection { connection ->
            connection.inTransaction {
                val now = clock.instant()
                lockForTransaction(LOCK_NAMESPACE, tokens.voucher(viewerUserId))
                val snapshot = snapshot(viewerUserId, conversationId, now) ?: refuse()
                if (!snapshot.viewer.activeParticipant) refuse()
                snapshot.stateFor(subjectUserId) ?: refuse()
                val row = snapshot.rows[subjectUserId]
                if (MetInPersonRules.canWithdraw(row, now)) {
                    val day = MetInPersonRules.day(now)
                    withdrawVouch(
                        pairToken = row!!.pairToken,
                        day = day,
                        withdrawWindowStart = day.minus(MetInPersonRules.WITHDRAW_WINDOW),
                    )
                    // A couple confirmation is taken back as a whole.
                    row.metPartnerUserId?.let { partnerUserId ->
                        withdrawPartnerVouch(
                            partnerPairToken = tokens.pair(viewerUserId, partnerUserId),
                            metPartnerUserId = subjectUserId,
                            day = day,
                        )
                    }
                }
                snapshot(viewerUserId, conversationId, now)?.stateFor(subjectUserId) ?: refuse()
            }
        }
    }

    private class Snapshot(
        val viewer: MetInPersonViewerFacts,
        val facts: Map<UUID, MetInPersonSubjectFacts>,
        val rows: Map<UUID, MetInPersonPairRow>,
        val states: List<MetInPersonOfferDto>,
    ) {
        fun stateFor(subjectUserId: UUID): MetInPersonOfferDto? = states.firstOrNull { it.subjectUserId == subjectUserId.toString() }
    }

    private fun Connection.snapshot(
        viewerUserId: UUID,
        conversationId: UUID,
        now: Instant,
    ): Snapshot? {
        val viewer = viewerFacts(viewerUserId, conversationId) ?: return null
        if (!viewer.activeParticipant) return null
        val subjects = subjectFacts(viewerUserId, conversationId)
        val tokenToSubject = subjects.associate { tokens.pair(viewerUserId, it.subjectUserId) to it.subjectUserId }
        val rows =
            pairRows(tokenToSubject.keys)
                .mapNotNull { row -> tokenToSubject[row.pairToken]?.let { it to row } }
                .toMap()
        val capUsed = countGivenSince(tokens.voucher(viewerUserId), MetInPersonRules.capWindowStart(now))
        return Snapshot(
            viewer = viewer,
            facts = subjects.associateBy { it.subjectUserId },
            rows = rows,
            states = MetInPersonRules.states(viewer, subjects, rows, capUsed, now),
        )
    }

    private fun String.toUuidOrRefuse(): UUID = runCatching { UUID.fromString(trim()) }.getOrNull() ?: refuse()

    private fun refuse(): Nothing = throw MessagingException(NOT_AVAILABLE)

    companion object {
        const val NOT_AVAILABLE = "MET_IN_PERSON_NOT_AVAILABLE"
        private const val LOCK_NAMESPACE = "met_in_person_voucher"
    }
}
