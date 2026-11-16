package com.pynapli.server.tonight

import com.pynapli.server.tonight.TonightSessionRepository.ApproveOutcome
import com.pynapli.server.tonight.TonightSessionRepository.MutateOutcome
import java.time.Duration
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

class JdbcTonightSessionRepository(
    private val dataSource: DataSource,
) : TonightSessionRepository {
    override suspend fun isPhotoOwnedBy(
        userId: UUID,
        photoAssetId: UUID,
    ): Boolean = dataSource.isTonightPhotoOwnedBy(userId, photoAssetId)

    override suspend fun hasActiveOrProposedSession(
        userId: UUID,
        now: Instant,
    ): Boolean = dataSource.hasActiveOrProposedTonightSession(userId, now)

    override suspend fun countSessionsCreatedSince(
        userId: UUID,
        since: Instant,
    ): Int = dataSource.countTonightSessionsCreatedSince(userId, since)

    override suspend fun findActiveLinkedPartner(userId: UUID): UUID? = dataSource.findActiveTonightLinkedPartner(userId)

    override suspend fun insertSessionIfAllowed(
        session: TonightSession,
        now: Instant,
        weeklyCap: TonightSessionRepository.WeeklyCap?,
    ): TonightSessionRepository.InsertOutcome = dataSource.insertTonightSessionIfAllowed(session, now, weeklyCap)

    override suspend fun approveParticipant(
        sessionId: UUID,
        userId: UUID,
        now: Instant,
    ): ApproveOutcome = dataSource.approveTonightParticipant(sessionId, userId, now)

    override suspend fun declineParticipant(
        sessionId: UUID,
        userId: UUID,
        now: Instant,
    ): MutateOutcome = dataSource.declineTonightParticipant(sessionId, userId, now)

    override suspend fun endSession(
        sessionId: UUID,
        userId: UUID,
        now: Instant,
    ): MutateOutcome = dataSource.endTonightSession(sessionId, userId, now)

    override suspend fun findById(sessionId: UUID): TonightSession? = dataSource.findTonightSessionById(sessionId)

    override suspend fun findCurrentForUser(
        userId: UUID,
        now: Instant,
    ): TonightSession? = dataSource.findCurrentTonightSessionForUser(userId, now)

    override suspend fun expireStale(now: Instant): Int = dataSource.expireStaleTonightSessions(now)

    override suspend fun claimSessionsDueForReminder(
        now: Instant,
        leadWindow: Duration,
    ): List<TonightSessionRepository.TonightReminderTarget> = dataSource.claimTonightSessionsDueForReminder(now, leadWindow)

    override suspend fun listActivePresenceSessions(now: Instant): List<TonightSessionRepository.TonightPresenceSession> = dataSource.listActiveTonightPresenceSessions(now)

    override suspend fun pauseSession(
        sessionId: UUID,
        now: Instant,
    ): Boolean = dataSource.pauseTonightSession(sessionId, now)

    override suspend fun resumeSession(sessionId: UUID): Boolean = dataSource.resumeTonightSession(sessionId)

    override suspend fun systemEndSession(
        sessionId: UUID,
        now: Instant,
    ): Boolean = dataSource.systemEndTonightSession(sessionId, now)
}
