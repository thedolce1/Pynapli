package com.pynapli.server.safety

import com.pynapli.server.media.MediaModerationEscalationPublisher
import com.pynapli.shared.network.dto.ReportTargetType
import java.util.UUID

class MediaModerationEscalationService(
    private val reportRepository: ReportRepository,
    private val riskSignalPublisher: MediaModerationRiskSignalPublisher,
) : MediaModerationEscalationPublisher {
    override suspend fun recordSevereMediaDecision(
        ownerUserId: UUID,
        mediaAssetId: UUID,
        albumKind: String,
        reason: String,
        reportCategory: String,
    ) {
        val report =
            reportRepository.createSystemReport(
                reportedUserId = ownerUserId,
                targetType = ReportTargetType.MEDIA,
                targetId = mediaAssetId.toString(),
                category = reportCategory,
                note = "Automated media moderation flagged severe content. reason=$reason album=$albumKind",
            )
        riskSignalPublisher.recordSevereMediaModerationFlag(
            reportId = report.id,
            userId = ownerUserId,
            mediaAssetId = mediaAssetId,
            albumKind = albumKind,
            reason = reason,
            category = reportCategory,
        )
    }
}
