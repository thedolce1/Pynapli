package com.pynapli.server.media

import com.pynapli.shared.network.dto.ReportCategory
import java.util.UUID

/** Decision a [MediaModerationScanner] returns for a freshly-uploaded asset. */
sealed interface MediaModerationDecision {
    /** Asset is safe to surface immediately. */
    data object Approved : MediaModerationDecision

    /** Asset is withheld for human review via the admin moderation console. */
    data object PendingReview : MediaModerationDecision

    /** Asset violates policy and must stay hidden. */
    data class Rejected(
        val reason: String,
        val severity: MediaModerationSeverity = MediaModerationSeverity.POLICY,
        val reportCategory: String = ReportCategory.EXPLICIT_PUBLIC_CONTENT,
    ) : MediaModerationDecision
}

enum class MediaModerationSeverity {
    POLICY,
    SEVERE,
}

/**
 * Runs on upload-complete to decide whether a media asset becomes visible. Public-facing discovery
 * queries only surface `processing_status = 'ready' AND moderation_status = 'approved'`, so any
 * non-approved decision keeps the asset hidden until a moderator (or a real scanner) approves it.
 */
interface MediaModerationScanner {
    suspend fun scan(asset: MediaAssetRecord): MediaModerationDecision
}

interface MediaModerationEscalationPublisher {
    suspend fun recordSevereMediaDecision(
        ownerUserId: UUID,
        mediaAssetId: UUID,
        albumKind: String,
        reason: String,
        reportCategory: String,
    )
}

object NoOpMediaModerationEscalationPublisher : MediaModerationEscalationPublisher {
    override suspend fun recordSevereMediaDecision(
        ownerUserId: UUID,
        mediaAssetId: UUID,
        albumKind: String,
        reason: String,
        reportCategory: String,
    ) = Unit
}

/** Dev/local: approves every upload immediately. */
class AutoApproveMediaModerationScanner : MediaModerationScanner {
    override suspend fun scan(asset: MediaAssetRecord): MediaModerationDecision = MediaModerationDecision.Approved
}

/**
 * Holds every upload for review (`PYNAPLI_MEDIA_MODERATION_PROVIDER=manual`). The default for
 * local/test when dev auto-approve is off; refused in production unless
 * `PYNAPLI_MEDIA_MODERATION_ALLOW_MANUAL_IN_PRODUCTION=true`. Staging and production default to
 * [CloudVisionMediaModerationScanner].
 */
class ManualReviewMediaModerationScanner : MediaModerationScanner {
    override suspend fun scan(asset: MediaAssetRecord): MediaModerationDecision = MediaModerationDecision.PendingReview
}
