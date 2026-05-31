package com.pynapli.server.account

import java.time.Instant
import java.util.UUID

interface AccountLifecycleRepository {
    suspend fun latestDataExportRequest(userId: UUID): AccountDataExportRecord?

    suspend fun createDataExportRequest(userId: UUID): AccountDataExportRecord

    suspend fun deleteAccount(userId: UUID): AccountDeletionResult?

    /** Atomically claims one `requested` export, moving it to `processing`. Null if none pending. */
    suspend fun claimNextPendingExport(): AccountDataExportRecord?

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
    val endedPartnerBenefitCount: Int = 0,
    val softDeletedMediaCount: Int = 0,
)
