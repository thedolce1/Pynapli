package com.pynapli.server.moderation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.sql.DataSource

class JdbcAdminRiskRepository(
    private val dataSource: DataSource,
) : AdminRiskRepository {
    override suspend fun listRiskStates(
        tierFilter: String,
        limit: Int,
        actorId: String,
        reason: String,
    ): List<AdminRiskStateRecord> =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.listRiskStatesWithAudit(
                    tierFilter = tierFilter,
                    limit = limit,
                    actorId = actorId,
                    reason = reason,
                )
            }
        }

    override suspend fun listRiskEvents(
        userId: UUID,
        limit: Int,
        actorId: String,
        reason: String,
    ): List<AdminRiskEventRecord> =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.listRiskEventsWithAudit(
                    userId = userId,
                    limit = limit,
                    actorId = actorId,
                    reason = reason,
                )
            }
        }

    override suspend fun setRiskTier(
        userId: UUID,
        tier: String,
        actorId: String,
        reason: String,
    ): AdminRiskActionRecord? =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.setRiskTierWithAudit(userId, tier, actorId, reason)
            }
        }

    override suspend fun markRiskReviewed(
        userId: UUID,
        actorId: String,
        reason: String,
    ): AdminRiskActionRecord? =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.markRiskReviewedWithAudit(userId, actorId, reason)
            }
        }

    override suspend fun lockAccount(
        userId: UUID,
        actorId: String,
        reason: String,
    ): AdminRiskActionRecord? =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.enforceAccountStatusWithAudit(
                    userId = userId,
                    nextStatus = "suspended",
                    action = "lock",
                    actorId = actorId,
                    reason = reason,
                )
            }
        }

    override suspend fun banAccount(
        userId: UUID,
        actorId: String,
        reason: String,
    ): AdminRiskActionRecord? =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.enforceAccountStatusWithAudit(
                    userId = userId,
                    nextStatus = "banned",
                    action = "ban",
                    actorId = actorId,
                    reason = reason,
                )
            }
        }
}
