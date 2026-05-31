package com.pynapli.server.media

/** Decision a [MediaModerationScanner] returns for a freshly-uploaded asset. */
sealed interface MediaModerationDecision {
    /** Asset is safe to surface immediately. */
    data object Approved : MediaModerationDecision

    /** Asset is withheld for human review via the admin moderation console. */
    data object PendingReview : MediaModerationDecision

    /** Asset violates policy and must stay hidden. */
    data class Rejected(
        val reason: String,
    ) : MediaModerationDecision
}

/**
 * Runs on upload-complete to decide whether a media asset becomes visible. Public-facing discovery
 * queries only surface `processing_status = 'ready' AND moderation_status = 'approved'`, so any
 * non-approved decision keeps the asset hidden until a moderator (or a real scanner) approves it.
 */
interface MediaModerationScanner {
    suspend fun scan(asset: MediaAssetRecord): MediaModerationDecision
}

/** Dev/local: approves every upload immediately. */
class AutoApproveMediaModerationScanner : MediaModerationScanner {
    override suspend fun scan(asset: MediaAssetRecord): MediaModerationDecision = MediaModerationDecision.Approved
}

/**
 * Production default: holds every upload for review. A real automated scanner (e.g. cloud
 * SafeSearch / a moderation API) can replace this behind the same interface without touching
 * MediaService.
 */
class ManualReviewMediaModerationScanner : MediaModerationScanner {
    override suspend fun scan(asset: MediaAssetRecord): MediaModerationDecision = MediaModerationDecision.PendingReview
}
