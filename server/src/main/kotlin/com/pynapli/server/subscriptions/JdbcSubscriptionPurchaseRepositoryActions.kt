package com.pynapli.server.subscriptions

import com.pynapli.server.db.withConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

internal class JdbcSubscriptionPurchaseRepositoryActions(
    private val dataSource: DataSource,
) {
    suspend fun upsertDevSubscriptionPurchase(
        userId: UUID,
        productId: String,
        tier: String,
        startsAt: Instant,
        endsAt: Instant,
    ): SubscriptionRecord =
        dataSource.withConnection { connection ->
            connection.upsertDevSubscriptionPurchaseRecord(userId, productId, tier, startsAt, endsAt)
        }

    suspend fun listPlayStoreSubscriptionsDueForVerification(
        staleBefore: Instant,
        renewalWindowEnd: Instant,
        limit: Int,
    ): List<PlayStoreSubscriptionVerificationCandidate> =
        dataSource.withConnection { connection ->
            connection.listPlayStoreSubscriptionsDueForVerificationRecords(staleBefore, renewalWindowEnd, limit)
        }

    suspend fun findPlayStoreSubscriptionByPurchaseToken(
        purchaseToken: String,
    ): PlayStoreSubscriptionVerificationCandidate? =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection -> connection.findPlayStoreSubscriptionByPurchaseTokenRecord(purchaseToken) }
        }

    suspend fun insertPlayStoreRtdnEvent(event: PlayStoreRtdnEventInsert): Boolean =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection -> connection.insertPlayStoreRtdnEventRecord(event) }
        }

    suspend fun updatePlayStoreRtdnEventStatus(
        messageId: String,
        processingStatus: String,
        errorCode: String?,
    ): Unit =
        dataSource.withConnection { connection ->
            connection.updatePlayStoreRtdnEventStatusRecord(messageId, processingStatus, errorCode)
        }

    suspend fun upsertPendingPlayStoreSubscriptionPurchase(
        userId: UUID,
        productId: String,
        purchaseToken: String,
        tier: String,
        now: Instant,
    ): SubscriptionRecord =
        dataSource.withConnection { connection ->
            connection.upsertPendingPlayStoreSubscriptionPurchaseRecord(userId, productId, purchaseToken, tier, now)
        }

    suspend fun upsertVerifiedPlayStoreSubscriptionPurchase(
        userId: UUID,
        productId: String,
        purchaseToken: String,
        orderId: String?,
        tier: String,
        status: String,
        externalStatus: String,
        acknowledgementState: String?,
        acknowledgedAt: Instant?,
        currentPeriodStart: Instant,
        currentPeriodEnd: Instant,
        verifiedAt: Instant,
    ): SubscriptionRecord =
        dataSource.withConnection { connection ->
            connection.upsertVerifiedPlayStoreSubscriptionPurchaseRecord(
                userId = userId,
                productId = productId,
                purchaseToken = purchaseToken,
                orderId = orderId,
                tier = tier,
                status = status,
                externalStatus = externalStatus,
                acknowledgementState = acknowledgementState,
                acknowledgedAt = acknowledgedAt,
                currentPeriodStart = currentPeriodStart,
                currentPeriodEnd = currentPeriodEnd,
                verifiedAt = verifiedAt,
            )
        }
}
