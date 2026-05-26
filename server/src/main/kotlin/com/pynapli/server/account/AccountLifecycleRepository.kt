package com.pynapli.server.account

import java.time.Instant
import java.util.UUID

interface AccountLifecycleRepository {
    suspend fun latestDataExportRequest(userId: UUID): AccountDataExportRecord?

    suspend fun createDataExportRequest(userId: UUID): AccountDataExportRecord

    suspend fun deleteAccount(userId: UUID): AccountDeletionResult?
}

data class AccountDataExportRecord(
    val id: UUID,
    val userId: UUID,
    val status: String,
    val requestedAt: Instant,
    val completedAt: Instant?,
    val expiresAt: Instant?,
    val downloadUrl: String?,
    val errorMessage: String?
)

data class AccountDeletionResult(
    val userId: UUID,
    val revokedSessionCount: Int
)
