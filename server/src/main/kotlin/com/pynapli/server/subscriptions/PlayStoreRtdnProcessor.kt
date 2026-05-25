package com.pynapli.server.subscriptions

import java.time.Clock

internal class PlayStoreRtdnProcessor(
    private val repository: SubscriptionRepository,
    private val clock: Clock,
    private val playStorePackageName: String,
    private val playStorePurchaseVerifier: PlayStorePurchaseVerifier,
    private val purchaseApplier: PlayStoreSubscriptionPurchaseApplier
) {
    suspend fun handle(envelope: PlayStoreRtdnEnvelope): PlayStoreRtdnProcessingResult {
        val notification = envelope.notification
        val eventInsert = PlayStoreRtdnEventInsert(
            messageId = envelope.messageId,
            packageName = notification.packageName,
            eventType = envelope.eventType,
            notificationType = envelope.notificationType,
            purchaseToken = envelope.purchaseToken,
            productId = envelope.productId,
            eventTime = envelope.eventTime,
            rawPayload = envelope.rawPayload
        )
        val inserted = repository.insertPlayStoreRtdnEvent(eventInsert)
        if (!inserted) {
            return envelope.result(status = "duplicate")
        }

        if (notification.packageName != playStorePackageName) {
            repository.updatePlayStoreRtdnEventStatus(
                messageId = envelope.messageId,
                processingStatus = "ignored",
                errorCode = "INVALID_PURCHASE_PACKAGE"
            )
            return envelope.result(status = "ignored", errorCode = "INVALID_PURCHASE_PACKAGE")
        }

        return when {
            notification.testNotification != null -> {
                repository.updatePlayStoreRtdnEventStatus(envelope.messageId, "processed")
                envelope.result(status = "processed")
            }
            notification.subscriptionNotification != null -> {
                processSubscriptionRtdn(envelope, notification.subscriptionNotification)
            }
            notification.voidedPurchaseNotification != null -> {
                processVoidedPurchaseRtdn(envelope, notification.voidedPurchaseNotification)
            }
            else -> {
                repository.updatePlayStoreRtdnEventStatus(
                    messageId = envelope.messageId,
                    processingStatus = "ignored",
                    errorCode = "UNSUPPORTED_RTDN_EVENT"
                )
                envelope.result(status = "ignored", errorCode = "UNSUPPORTED_RTDN_EVENT")
            }
        }
    }

    private suspend fun processSubscriptionRtdn(
        envelope: PlayStoreRtdnEnvelope,
        notification: PlayStoreSubscriptionNotificationDto
    ): PlayStoreRtdnProcessingResult {
        val purchaseToken = notification.purchaseToken?.trim()?.takeIf { it.length in 16..4096 }
        if (purchaseToken == null) {
            repository.updatePlayStoreRtdnEventStatus(envelope.messageId, "ignored", "INVALID_PURCHASE_TOKEN")
            return envelope.result(
                status = "ignored",
                purchaseToken = notification.purchaseToken,
                productId = notification.subscriptionId,
                errorCode = "INVALID_PURCHASE_TOKEN"
            )
        }

        val stored = repository.findPlayStoreSubscriptionByPurchaseToken(purchaseToken)
        val productId = notification.subscriptionId?.trim()?.takeIf { it.isNotEmpty() }
            ?: stored?.productId
        val verification = if (productId == null) {
            playStorePurchaseVerifier.verifySubscriptionPurchaseByToken(
                packageName = playStorePackageName,
                purchaseToken = purchaseToken
            )
        } else {
            playStorePurchaseVerifier.verifySubscriptionPurchase(
                packageName = playStorePackageName,
                productId = productId,
                purchaseToken = purchaseToken
            )
        }

        return when (verification) {
            is PlayStorePurchaseVerificationResult.Invalid -> {
                processInvalidSubscriptionVerification(
                    envelope = envelope,
                    stored = stored,
                    purchaseToken = purchaseToken,
                    productId = productId,
                    errorCode = verification.code
                )
            }
            PlayStorePurchaseVerificationResult.VerificationUnavailable -> {
                repository.updatePlayStoreRtdnEventStatus(
                    messageId = envelope.messageId,
                    processingStatus = "retry",
                    errorCode = "RTDN_VERIFICATION_UNAVAILABLE"
                )
                throw SubscriptionException("RTDN_VERIFICATION_UNAVAILABLE")
            }
            is PlayStorePurchaseVerificationResult.Verified -> {
                processVerifiedSubscriptionVerification(
                    envelope = envelope,
                    stored = stored,
                    purchaseToken = purchaseToken,
                    purchase = verification.purchase
                )
            }
        }
    }

    private suspend fun processInvalidSubscriptionVerification(
        envelope: PlayStoreRtdnEnvelope,
        stored: PlayStoreSubscriptionVerificationCandidate?,
        purchaseToken: String,
        productId: String?,
        errorCode: String
    ): PlayStoreRtdnProcessingResult {
        if (stored != null) {
            purchaseApplier.revokeStoredPurchase(
                stored = stored,
                purchaseToken = purchaseToken,
                externalStatus = errorCode,
                verifiedAt = clock.instant()
            )
            repository.updatePlayStoreRtdnEventStatus(envelope.messageId, "processed", errorCode)
            return envelope.result(
                status = "processed",
                purchaseToken = purchaseToken,
                productId = stored.productId,
                subscriptionStatus = "revoked",
                errorCode = errorCode
            )
        }

        repository.updatePlayStoreRtdnEventStatus(envelope.messageId, "ignored", errorCode)
        return envelope.result(
            status = "ignored",
            purchaseToken = purchaseToken,
            productId = productId,
            errorCode = errorCode
        )
    }

    private suspend fun processVerifiedSubscriptionVerification(
        envelope: PlayStoreRtdnEnvelope,
        stored: PlayStoreSubscriptionVerificationCandidate?,
        purchaseToken: String,
        purchase: VerifiedPlayStoreSubscriptionPurchase
    ): PlayStoreRtdnProcessingResult {
        val tier = playStoreTierForProduct(purchase.productId)
            ?: stored?.tier
            ?: run {
                repository.updatePlayStoreRtdnEventStatus(
                    envelope.messageId,
                    "ignored",
                    "INVALID_SUBSCRIPTION_PRODUCT"
                )
                return envelope.result(
                    status = "ignored",
                    purchaseToken = purchaseToken,
                    productId = purchase.productId,
                    errorCode = "INVALID_SUBSCRIPTION_PRODUCT"
                )
            }
        val userId = stored?.userId ?: run {
            repository.updatePlayStoreRtdnEventStatus(envelope.messageId, "ignored", "PURCHASE_NOT_LINKED")
            return envelope.result(
                status = "ignored",
                purchaseToken = purchaseToken,
                productId = purchase.productId,
                errorCode = "PURCHASE_NOT_LINKED"
            )
        }
        val subscription = purchaseApplier.applyVerifiedPurchase(
            userId = userId,
            tier = tier,
            purchase = purchase,
            verifiedAt = clock.instant()
        )
        repository.updatePlayStoreRtdnEventStatus(envelope.messageId, "processed")
        return envelope.result(
            status = "processed",
            purchaseToken = purchaseToken,
            productId = purchase.productId,
            subscriptionStatus = subscription.status
        )
    }

    private suspend fun processVoidedPurchaseRtdn(
        envelope: PlayStoreRtdnEnvelope,
        notification: PlayStoreVoidedPurchaseNotificationDto
    ): PlayStoreRtdnProcessingResult {
        val purchaseToken = notification.purchaseToken?.trim()?.takeIf { it.length in 16..4096 }
        if (purchaseToken == null) {
            repository.updatePlayStoreRtdnEventStatus(envelope.messageId, "ignored", "INVALID_PURCHASE_TOKEN")
            return envelope.result(
                status = "ignored",
                purchaseToken = notification.purchaseToken,
                errorCode = "INVALID_PURCHASE_TOKEN"
            )
        }
        val stored = repository.findPlayStoreSubscriptionByPurchaseToken(purchaseToken)
        if (stored == null || notification.productType != PLAY_STORE_PRODUCT_TYPE_SUBSCRIPTION) {
            repository.updatePlayStoreRtdnEventStatus(envelope.messageId, "ignored", "PURCHASE_NOT_LINKED")
            return envelope.result(
                status = "ignored",
                purchaseToken = purchaseToken,
                errorCode = "PURCHASE_NOT_LINKED"
            )
        }
        purchaseApplier.revokeStoredPurchase(
            stored = stored,
            purchaseToken = purchaseToken,
            orderId = notification.orderId,
            externalStatus = "VOIDED_PURCHASE_REFUND_TYPE_${notification.refundType ?: 0}",
            verifiedAt = clock.instant()
        )
        repository.updatePlayStoreRtdnEventStatus(envelope.messageId, "processed")
        return envelope.result(
            status = "processed",
            purchaseToken = purchaseToken,
            productId = stored.productId,
            subscriptionStatus = "revoked"
        )
    }

    private fun PlayStoreRtdnEnvelope.result(
        status: String,
        errorCode: String? = null,
        productId: String? = this.productId,
        purchaseToken: String? = this.purchaseToken,
        subscriptionStatus: String? = null
    ): PlayStoreRtdnProcessingResult =
        PlayStoreRtdnProcessingResult(
            status = status,
            eventType = eventType,
            messageId = messageId,
            purchaseToken = purchaseToken,
            productId = productId,
            subscriptionStatus = subscriptionStatus,
            errorCode = errorCode
        )

    private companion object {
        const val PLAY_STORE_PRODUCT_TYPE_SUBSCRIPTION = 1
    }
}
