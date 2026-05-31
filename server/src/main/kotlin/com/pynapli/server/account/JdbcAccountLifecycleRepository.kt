package com.pynapli.server.account

import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

class JdbcAccountLifecycleRepository(
    dataSource: DataSource,
) : AccountLifecycleRepository {
    private val exportActions = JdbcAccountDataExportActions(dataSource)
    private val deletionActions = JdbcAccountDeletionActions(dataSource)

    override suspend fun latestDataExportRequest(userId: UUID): AccountDataExportRecord? = exportActions.latestDataExportRequest(userId)

    override suspend fun createDataExportRequest(userId: UUID): AccountDataExportRecord = exportActions.createDataExportRequest(userId)

    override suspend fun deleteAccount(userId: UUID): AccountDeletionResult? = deletionActions.deleteAccount(userId)

    override suspend fun claimNextPendingExport(): AccountDataExportRecord? = exportActions.claimNextPendingExport()

    override suspend fun markExportReady(
        requestId: UUID,
        downloadUrl: String,
        completedAt: Instant,
        expiresAt: Instant,
    ): AccountDataExportRecord? = exportActions.markExportReady(requestId, downloadUrl, completedAt, expiresAt)

    override suspend fun markExportFailed(
        requestId: UUID,
        errorMessage: String,
    ): AccountDataExportRecord? = exportActions.markExportFailed(requestId, errorMessage)

    override suspend fun exportRequestById(requestId: UUID): AccountDataExportRecord? = exportActions.exportRequestById(requestId)
}
