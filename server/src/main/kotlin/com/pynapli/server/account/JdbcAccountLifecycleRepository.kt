package com.pynapli.server.account

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
}
