package com.pynapli.server.safety

import com.pynapli.server.db.withConnection
import java.util.UUID
import javax.sql.DataSource

class JdbcAppealRepository(
    private val dataSource: DataSource,
) : AppealRepository {
    override suspend fun createAppeal(
        userId: UUID,
        actionType: String,
        targetType: String,
        targetId: String?,
        relatedReportId: UUID?,
        reason: String,
        appealIdFactory: () -> UUID,
    ): CreateAppealResult =
        dataSource.withConnection { connection ->
            connection.createAppealInTransaction(
                userId = userId,
                actionType = actionType,
                targetType = targetType,
                targetId = targetId,
                relatedReportId = relatedReportId,
                reason = reason,
                appealIdFactory = appealIdFactory,
            )
        }

    override suspend fun listAppeals(
        userId: UUID,
        limit: Int,
    ): List<AppealRecord> =
        dataSource.withConnection { connection ->
            connection.selectAppealsByUser(userId, limit)
        }

    override suspend fun withdrawAppeal(
        userId: UUID,
        appealId: UUID,
    ): AppealRecord? =
        dataSource.withConnection { connection ->
            connection.withdrawAppealByUser(userId, appealId)
        }
}
