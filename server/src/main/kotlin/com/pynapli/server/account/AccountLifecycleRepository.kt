package com.pynapli.server.account

import java.time.Duration
import java.time.Instant
import java.util.UUID

interface AccountLifecycleRepository {
    suspend fun latestDataExportRequest(userId: UUID): AccountDataExportRecord?

    suspend fun createDataExportRequest(userId: UUID): AccountDataExportRecord

    suspend fun deleteAccount(userId: UUID): AccountDeletionResult?

    /**
     * Atomically claims one export, moving it to `processing`: a `requested` one, or a `processing`
     * one whose claim is older than [staleAfter]. Skips requests that used [maxAttempts]. Null if none.
     */
    suspend fun claimNextPendingExport(
        staleAfter: Duration,
        maxAttempts: Int,
    ): AccountDataExportRecord?

    /** Fails stale `processing` exports that have no attempts left; returns how many. */
    suspend fun failAbandonedExports(
        staleAfter: Duration,
        maxAttempts: Int,
        errorMessage: String,
    ): Int

    suspend fun markExportReady(
        requestId: UUID,
        downloadUrl: String,
        completedAt: Instant,
        expiresAt: Instant,
    ): AccountDataExportRecord?

    suspend fun markExportFailed(
        requestId: UUID,
        errorMessage: String,
    ): AccountDataExportRecord?

    suspend fun exportRequestById(requestId: UUID): AccountDataExportRecord?
}

data class AccountDataExportRecord(
    val id: UUID,
    val userId: UUID,
    val status: String,
    val requestedAt: Instant,
    val completedAt: Instant?,
    val expiresAt: Instant?,
    val downloadUrl: String?,
    val errorMessage: String?,
)

data class AccountDeletionResult(
    val userId: UUID,
    val revokedSessionCount: Int,
    val purgedLocationCount: Int = 0,
    val endedPartnerLinkCount: Int = 0,
    val softDeletedMediaCount: Int = 0,
    /** Apple grants to revoke after the commit (read in the deleting transaction, then scrubbed). */
    val appleTokens: List<com.pynapli.server.auth.social.SealedAppleToken> = emptyList(),
)
