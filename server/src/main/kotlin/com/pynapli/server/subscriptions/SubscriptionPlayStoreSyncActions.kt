package com.pynapli.server.subscriptions

import java.time.Clock
import java.time.Duration

internal class SubscriptionPlayStoreSyncActions(
    private val repository: SubscriptionRepository,
    private val clock: Clock,
    private val playStorePackageName: String,
    private val playStorePurchaseVerifier: PlayStorePurchaseVerifier,
    private val purchaseApplier: PlayStoreSubscriptionPurchaseApplier,
    private val playStoreSyncStaleAfter: Duration,
    private val playStoreSyncRenewalLookahead: Duration,
) {
    suspend fun syncDuePlayStoreSubscriptions(limit: Int): PlayStoreSubscriptionSyncSummary {
        val now = clock.instant()
        val normalizedLimit = limit.coerceIn(1, PLAY_STORE_SYNC_MAX_LIMIT)
        val candidates =
            repository.listPlayStoreSubscriptionsDueForVerification(
                staleBefore = now.minus(playStoreSyncStaleAfter),
                renewalWindowEnd = now.plus(playStoreSyncRenewalLookahead),
                limit = normalizedLimit,
            )
        var verified = 0
        var active = 0
        var grace = 0
        var pending = 0
        var expired = 0
        var revoked = 0
        var invalid = 0
        var unavailable = 0

        candidates.forEach { candidate ->
            when (
                val verification =
                    playStorePurchaseVerifier.verifySubscriptionPurchase(
                        packageName = playStorePackageName,
                        productId = candidate.productId,
                        purchaseToken = candidate.purchaseToken,
                    )
            ) {
                is PlayStorePurchaseVerificationResult.Invalid -> {
                    invalid++
                    revoked++
                    purchaseApplier.revokeStoredPurchase(
                        stored = candidate,
                        externalStatus = verification.code,
                        verifiedAt = now,
                    )
                }
                PlayStorePurchaseVerificationResult.VerificationUnavailable -> unavailable++
                is PlayStorePurchaseVerificationResult.Verified -> {
                    verified++
                    val subscription =
                        purchaseApplier.applyVerifiedPurchase(
                            userId = candidate.userId,
                            tier = candidate.tier,
                            purchase = verification.purchase,
                            verifiedAt = now,
                        )
                    when (subscription.status) {
                        "active" -> active++
                        "grace" -> grace++
                        "expired" -> expired++
                        "revoked" -> revoked++
                        else -> pending++
                    }
                }
            }
        }

        return PlayStoreSubscriptionSyncSummary(
            scanned = candidates.size,
            verified = verified,
            active = active,
            grace = grace,
            pending = pending,
            expired = expired,
            revoked = revoked,
            invalid = invalid,
            unavailable = unavailable,
        )
    }

    private companion object {
        private const val PLAY_STORE_SYNC_MAX_LIMIT = 500
    }
}
