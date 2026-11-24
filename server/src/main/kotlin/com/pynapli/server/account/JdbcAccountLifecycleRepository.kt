package com.pynapli.server.account

import java.time.Duration
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

class JdbcAccountLifecycleRepository(
    dataSource: DataSource,
    // The server's contact block keys. Required on purpose: built without the configured keys,
    // deletion would release nothing (CoreDependencies passes them; tests pass the local ones).
    contactBlockTokens: com.pynapli.server.contactblocks.ContactBlockTokens,
) : AccountLifecycleRepository {
    private val exportActions = JdbcAccountDataExportActions(dataSource)
    private val deletionActions = JdbcAccountDeletionActions(dataSource, contactBlockTokens)

    override suspend fun latestDataExportRequest(userId: UUID): AccountDataExportRecord? = exportActions.latestDataExportRequest(userId)

    override suspend fun createDataExportRequest(userId: UUID): AccountDataExportRecord = exportActions.createDataExportRequest(userId)

    override suspend fun deleteAccount(userId: UUID): AccountDeletionResult? = deletionActions.deleteAccount(userId)

    override suspend fun claimNextPendingExport(
        staleAfter: Duration,
        maxAttempts: Int,
    ): AccountDataExportRecord? = exportActions.claimNextPendingExport(staleAfter, maxAttempts)

    override suspend fun failAbandonedExports(
        staleAfter: Duration,
        maxAttempts: Int,
        errorMessage: String,
    ): Int = exportActions.failAbandonedExports(staleAfter, maxAttempts, errorMessage)

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
