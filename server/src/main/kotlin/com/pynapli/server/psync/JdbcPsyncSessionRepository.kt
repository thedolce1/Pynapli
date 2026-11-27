package com.pynapli.server.psync

import com.pynapli.server.db.withConnection
import com.pynapli.server.psync.PsyncSessionRepository.ActiveLink
import com.pynapli.server.psync.PsyncSessionRepository.CompleteOutcome
import com.pynapli.server.psync.PsyncSessionRepository.MutateOutcome
import com.pynapli.server.psync.PsyncSessionRepository.RecordActionOutcome
import com.pynapli.server.psync.PsyncSessionRepository.StartOutcome
import com.pynapli.server.swipe.markMatchSideEffectsDelivered
import kotlinx.serialization.json.Json
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

class JdbcPsyncSessionRepository(
    private val dataSource: DataSource,
    private val json: Json = Json,
) : PsyncSessionRepository {
    override suspend fun findActiveLink(userId: UUID): ActiveLink? = dataSource.findPsyncActiveLink(userId)

    override suspend fun hasNonTerminalSessionForLink(linkId: UUID): Boolean = dataSource.hasNonTerminalPsyncSessionForLink(linkId)

    override suspend fun countStartedSessionsSince(
        initiatorUserId: UUID,
        since: Instant,
    ): Int = dataSource.countStartedPsyncSessionsSince(initiatorUserId, since)

    override suspend fun insertSession(
        session: PsyncSession,
        participants: List<PsyncSessionParticipant>,
    ): PsyncSession? = dataSource.insertPsyncSession(json, session, participants)

    override suspend fun accept(
        sessionId: UUID,
        userId: UUID,
        now: Instant,
    ): MutateOutcome = dataSource.acceptPsyncSession(json, sessionId, userId, now)

    override suspend fun deny(
        sessionId: UUID,
        userId: UUID,
        now: Instant,
    ): MutateOutcome = dataSource.denyPsyncSession(json, sessionId, userId, now)

    override suspend fun suggestFilters(
        sessionId: UUID,
        userId: UUID,
        filtersJson: String,
        now: Instant,
    ): MutateOutcome = dataSource.suggestPsyncFilters(json, sessionId, userId, filtersJson, now)

    override suspend fun leave(
        sessionId: UUID,
        userId: UUID,
        now: Instant,
    ): MutateOutcome = dataSource.leavePsyncSession(json, sessionId, userId, now)

    override suspend fun findById(
        sessionId: UUID,
        withDisplayNames: Boolean,
    ): PsyncSession? = dataSource.findPsyncSessionById(json, sessionId, withDisplayNames)

    override suspend fun findCurrentForUser(
        userId: UUID,
        now: Instant,
    ): PsyncSession? = dataSource.findCurrentPsyncSessionForUser(json, userId, now)

    override suspend fun expireStale(now: Instant): List<PsyncExpiredSession> = dataSource.expireStalePsyncSessions(now)

    override suspend fun listOverdueStartedSessions(now: Instant): List<PsyncOverdueSession> = dataSource.listOverdueStartedPsyncSessions(now)

    override suspend fun resetLocation(
        sessionId: UUID,
        travelLocationId: UUID?,
        locationLabel: String?,
        now: Instant,
    ): MutateOutcome = dataSource.resetPsyncLocation(json, sessionId, travelLocationId, locationLabel, now)

    override suspend fun start(
        sessionId: UUID,
        userId: UUID,
        now: Instant,
    ): StartOutcome = dataSource.startPsyncSession(json, sessionId, userId, now)

    override suspend fun activateWithDeck(
        sessionId: UUID,
        deckSessionId: UUID,
        cards: List<PsyncSessionCard>,
        now: Instant,
    ): MutateOutcome = dataSource.activatePsyncWithDeck(json, sessionId, deckSessionId, cards, now)

    override suspend fun alreadySettledTargets(
        partnerUserIds: List<UUID>,
        targetUserIds: List<UUID>,
    ): Set<UUID> = dataSource.psyncAlreadySettledTargets(partnerUserIds, targetUserIds)

    override suspend fun partnerFilterMatches(
        viewers: List<UUID>,
        candidateIds: List<UUID>,
        filters: com.pynapli.server.discovery.DiscoveryFilters,
        hiddenFromViewerIds: Set<UUID>,
    ): Set<UUID> =
        dataSource.withConnection { connection ->
            com.pynapli.server.discovery.PartnerFilterSql.matchingCandidateIds(
                connection = connection,
                viewers = viewers,
                candidateIds = candidateIds,
                filters = filters,
                hiddenFromViewerIds = hiddenFromViewerIds,
            )
        }

    override suspend fun listDeckCards(sessionId: UUID): List<PsyncSessionCard> = dataSource.listPsyncDeckCards(json, sessionId)

    override suspend fun listActionsForActor(
        sessionId: UUID,
        actorUserId: UUID,
    ): List<PsyncSessionAction> = dataSource.listPsyncActionsForActor(sessionId, actorUserId)

    override suspend fun findCardByTokenHash(
        sessionId: UUID,
        cardTokenHash: String,
    ): PsyncSessionCard? = dataSource.findPsyncCardByTokenHash(json, sessionId, cardTokenHash)

    override suspend fun recordAction(
        sessionId: UUID,
        actorUserId: UUID,
        targetUserId: UUID,
        action: String,
        now: Instant,
        actionIdFactory: () -> UUID,
        consumedInventoryId: UUID?,
    ): RecordActionOutcome = dataSource.recordPsyncAction(json, sessionId, actorUserId, targetUserId, action, now, actionIdFactory, consumedInventoryId)

    override suspend fun claimUndeliveredPineappleRefunds(): List<UUID> = dataSource.claimUndeliveredPsyncPineappleRefunds()

    override suspend fun countActionsForActor(
        sessionId: UUID,
        actorUserId: UUID,
    ): Int = dataSource.countPsyncActionsForActor(sessionId, actorUserId)

    override suspend fun transitionStatus(
        sessionId: UUID,
        userId: UUID,
        fromStatus: String,
        toStatus: String,
    ): MutateOutcome = dataSource.transitionPsyncStatus(json, sessionId, userId, fromStatus, toStatus)

    override suspend fun complete(
        sessionId: UUID,
        userId: UUID,
        now: Instant,
        matchIdFactory: () -> UUID,
    ): CompleteOutcome = dataSource.completePsyncSession(json, sessionId, userId, now, matchIdFactory)

    override suspend fun markSideEffectsDelivered(
        ids: List<UUID>,
        now: Instant,
    ) {
        if (ids.isEmpty()) return
        dataSource.withConnection { connection -> connection.markMatchSideEffectsDelivered(ids, now) }
    }

    override suspend fun listSessionMatches(sessionId: UUID): List<PsyncSessionMatch> = dataSource.listPsyncSessionMatches(sessionId)

    override suspend fun countDeckCards(sessionId: UUID): Int = dataSource.countPsyncDeckCards(sessionId)

    override suspend fun countBothLiked(sessionId: UUID): Int = dataSource.countPsyncBothLiked(sessionId)

    override suspend fun isSoloShareEnabled(userId: UUID): Boolean = dataSource.isPsyncSoloShareEnabled(userId)
}
