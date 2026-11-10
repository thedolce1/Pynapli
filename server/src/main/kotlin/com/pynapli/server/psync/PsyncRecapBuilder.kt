package com.pynapli.server.psync

import com.pynapli.shared.network.dto.PsyncMatchDto
import com.pynapli.shared.network.dto.PsyncRecapDto
import java.util.UUID

internal class PsyncRecapBuilder(
    private val repository: PsyncSessionRepository,
) {
    /**
     * Recap is recap, NOT a score (PSYNC_SPEC): aggregate counts only. soloMatchCount is always
     * the VIEWER's OWN solo passthrough matches — a partner's solo matches stay private.
     */
    suspend fun buildRecap(
        session: PsyncSession,
        viewerUserId: UUID,
    ): PsyncRecapDto {
        val matches = repository.listSessionMatches(session.id)
        val together = matches.filter { it.kind == "together" }
        val mySolo = matches.count { it.kind == "solo" && it.ownerUserId == viewerUserId }
        // PSYNC_SPEC: solo passthrough is private by default. Only surface the partner's solo
        // count if THEY opted in (discovery_settings.share_psync_solo_matches); else null.
        val partnerId = session.participants.firstOrNull { it.userId != viewerUserId }?.userId
        val partnerSolo =
            if (partnerId != null && repository.isSoloShareEnabled(partnerId)) {
                matches.count { it.kind == "solo" && it.ownerUserId == partnerId }
            } else {
                null
            }
        return PsyncRecapDto(
            sessionId = session.id.toString(),
            status = session.status,
            // What the viewer actually got through, not the deck size: a session can end early
            // (a partner left, the deadline passed) with cards nobody reached.
            profilesReviewed = repository.countActionsForActor(session.id, viewerUserId),
            bothLikedCount = repository.countBothLiked(session.id),
            togetherMatchCount = together.size,
            soloMatchCount = mySolo,
            partnerSoloMatchCount = partnerSolo,
            startedAt = session.startedAt?.toString(),
            endedAt = session.endedAt?.toString(),
            filtersSummary = session.filters.toPsyncFiltersSummary(),
            togetherMatches =
                together.map {
                    PsyncMatchDto(
                        targetUserId = it.targetUserId.toString(),
                        displayName = it.displayName,
                        kind = it.kind,
                    )
                },
        )
    }
}
