package com.pynapli.server.metinperson

import com.pynapli.server.contactblocks.ContactBlockVisibility
import com.pynapli.server.db.inTransaction
import com.pynapli.server.db.lockForTransaction
import com.pynapli.server.db.withConnection
import com.pynapli.server.messaging.MessagingException
import com.pynapli.server.messaging.isConversationHiddenFor
import com.pynapli.server.metinperson.JdbcMetInPersonSql.conversationPartners
import com.pynapli.server.metinperson.JdbcMetInPersonSql.countGivenSince
import com.pynapli.server.metinperson.JdbcMetInPersonSql.keyMatches
import com.pynapli.server.metinperson.JdbcMetInPersonSql.pairRows
import com.pynapli.server.metinperson.JdbcMetInPersonSql.subjectFacts
import com.pynapli.server.metinperson.JdbcMetInPersonSql.upsertVouch
import com.pynapli.server.metinperson.JdbcMetInPersonSql.viewerFacts
import com.pynapli.server.metinperson.JdbcMetInPersonSql.withdrawPartnerVouch
import com.pynapli.server.metinperson.JdbcMetInPersonSql.withdrawVouch
import com.pynapli.shared.network.dto.ConfirmMetInPersonRequestDto
import com.pynapli.shared.network.dto.MetInPersonOfferDto
import com.pynapli.shared.network.dto.MetInPersonOffersDto
import com.pynapli.shared.network.dto.WithdrawMetInPersonRequestDto
import java.sql.Connection
import java.time.Clock
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * The voucher's side of "Met in person" (TRUST_AND_SAFETY_SPEC "Met in person").
 *
 * - No notifier, broadcaster, Activity or outbox dependency at all: nothing here can reach the subject.
 * - Every answer is computed from the viewer's own rows only; nothing depends on other people's
 *   confirmations, a count or a badge.
 * - Every refusal is the one code `MET_IN_PERSON_NOT_AVAILABLE`; withdraw never refuses once the
 *   conversation is (or was) the viewer's.
 * - Nothing stores or logs a user id: only K3 tokens. The viewer's own rows are found by recomputing
 *   their pair tokens over everyone they have shared a conversation with.
 * - The menu asks for offers on its own request (`GET .../met-in-person`), never on the read mark, so a
 *   failure here can never stop a conversation from opening.
 * - "Hide from people you know" (viewer side, the messaging rule): a conversation whose every current
 *   co-participant hides from the viewer answers exactly like an unknown conversation, and anyone
 *   hiding from the viewer is left out of every answer (never offered, never confirmable, never named
 *   as a partner). Exempt pairs carry no active row, so they are unaffected.
 */
class MetInPersonService(
    private val dataSource: DataSource,
    private val tokens: MetInPersonTokens,
    // Required, so a dropped wiring fails the build instead of showing hidden people.
    private val contactBlockVisibility: ContactBlockVisibility,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun offers(
        viewerUserId: UUID,
        conversationIdText: String,
    ): MetInPersonOffersDto {
        val conversationId = conversationIdText.toUuidOrRefuse()
        val hidden = contactBlockVisibility.hiddenFrom(viewerUserId)
        return dataSource.withConnection { connection ->
            val now = clock.instant()
            if (!connection.keyMatches(tokens, now)) refuse()
            val snapshot = connection.snapshot(viewerUserId, conversationId, now, hidden) ?: refuse()
            MetInPersonOffersDto(offers = snapshot.states.filter(MetInPersonRules::actionable))
        }
    }

    suspend fun confirm(
        viewerUserId: UUID,
        conversationIdText: String,
        request: ConfirmMetInPersonRequestDto,
    ): MetInPersonOfferDto {
        val conversationId = conversationIdText.toUuidOrRefuse()
        val subjectUserId = request.subjectUserId.toUuidOrRefuse()
        val hidden = contactBlockVisibility.hiddenFrom(viewerUserId)
        return dataSource.withConnection { connection ->
            connection.inTransaction {
                val now = clock.instant()
                if (!keyMatches(tokens, now)) refuse()
                // Serialises one voucher's writes so the monthly cap cannot be raced past. The lock key
                // is a token, so no user id is handed to the database here.
                lockForTransaction(LOCK_NAMESPACE, tokens.auditVoucher(viewerUserId))
                val snapshot = snapshot(viewerUserId, conversationId, now, hidden) ?: refuse()
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
                val linkToken = if (partnerUserId != null) subject.partnerLinkId?.let(tokens::link) else null
                val day = MetInPersonRules.day(now)
                upsertVouch(
                    pairToken = tokens.pair(viewerUserId, subjectUserId),
                    subjectToken = tokens.subject(subjectUserId),
                    partnerToken = partnerUserId?.let(tokens::partner),
                    linkToken = linkToken,
                    day = day,
                    expiryCutoff = MetInPersonRules.expiryCutoff(now),
                )
                if (partnerUserId != null) {
                    upsertVouch(
                        pairToken = tokens.pair(viewerUserId, partnerUserId),
                        subjectToken = tokens.subject(partnerUserId),
                        partnerToken = tokens.partner(subjectUserId),
                        linkToken = linkToken,
                        day = day,
                        expiryCutoff = MetInPersonRules.expiryCutoff(now),
                    )
                }
                snapshot(viewerUserId, conversationId, now, hidden)?.stateFor(subjectUserId) ?: refuse()
            }
        }
    }

    /** Works for 30 days after giving, even after the viewer left the conversation. */
    suspend fun withdraw(
        viewerUserId: UUID,
        conversationIdText: String,
        request: WithdrawMetInPersonRequestDto,
    ): MetInPersonOfferDto {
        val conversationId = conversationIdText.toUuidOrRefuse()
        val subjectUserId = request.subjectUserId.toUuidOrRefuse()
        val hidden = contactBlockVisibility.hiddenFrom(viewerUserId)
        return dataSource.withConnection { connection ->
            connection.inTransaction {
                val now = clock.instant()
                if (!keyMatches(tokens, now)) refuse()
                lockForTransaction(LOCK_NAMESPACE, tokens.auditVoucher(viewerUserId))
                val snapshot = snapshot(viewerUserId, conversationId, now, hidden) ?: refuse()
                snapshot.stateFor(subjectUserId) ?: refuse()
                val row = snapshot.rows[subjectUserId]
                if (row != null && MetInPersonRules.canWithdraw(row, now)) {
                    val day = MetInPersonRules.day(now)
                    withdrawVouch(
                        pairToken = row.pairToken,
                        day = day,
                        withdrawWindowStart = day.minus(MetInPersonRules.WITHDRAW_WINDOW),
                    )
                    // A couple confirmation is taken back as a whole.
                    row.partnerToken?.let { partnerToken ->
                        snapshot.partners
                            .firstOrNull { tokens.partner(it) == partnerToken }
                            ?.let { partnerUserId ->
                                withdrawPartnerVouch(
                                    partnerPairToken = tokens.pair(viewerUserId, partnerUserId),
                                    partnerTokenOfSubject = tokens.partner(subjectUserId),
                                    day = day,
                                )
                            }
                    }
                }
                snapshot(viewerUserId, conversationId, now, hidden)?.stateFor(subjectUserId) ?: refuse()
            }
        }
    }

    private class Snapshot(
        val facts: Map<UUID, MetInPersonSubjectFacts>,
        val rows: Map<UUID, MetInPersonPairRow>,
        val partners: Set<UUID>,
        val states: List<MetInPersonOfferDto>,
    ) {
        fun stateFor(subjectUserId: UUID): MetInPersonOfferDto? = states.firstOrNull { it.subjectUserId == subjectUserId.toString() }
    }

    private fun Connection.snapshot(
        viewerUserId: UUID,
        conversationId: UUID,
        now: Instant,
        // Everyone hiding from the viewer (ContactBlockVisibility.hiddenFrom, read before the transaction).
        hiddenFromViewer: Set<UUID>,
    ): Snapshot? {
        val viewer = viewerFacts(viewerUserId, conversationId) ?: return null
        if (!viewer.participant) return null
        val allSubjects = subjectFacts(viewerUserId, conversationId)
        // Messaging's rule: nobody but people hiding from the viewer here means the conversation isn't theirs.
        val currentOthers = allSubjects.filter { it.currentParticipant }.map { it.subjectUserId }
        if (isConversationHiddenFor(currentOthers, hiddenFromViewer)) return null
        // Like the participants messaging shows: people hiding from the viewer are left out (so they are
        // never offered, confirmable, or named as someone's partner).
        val subjects = allSubjects.filterNot { it.subjectUserId in hiddenFromViewer }
        val partners = conversationPartners(viewerUserId)
        val subjectByToken = subjects.associate { tokens.pair(viewerUserId, it.subjectUserId) to it.subjectUserId }
        val stored = pairRows(subjectByToken.keys)
        val rows = stored.mapNotNull { (token, row) -> subjectByToken[token]?.let { it to row } }.toMap()
        val capUsed =
            countGivenSince(
                partners.map { tokens.pair(viewerUserId, it) },
                MetInPersonRules.capWindowStart(now),
            )
        return Snapshot(
            facts = subjects.associateBy { it.subjectUserId },
            rows = rows,
            partners = partners,
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
