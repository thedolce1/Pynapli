package com.pynapli.server.subscriptions

import com.pynapli.shared.network.dto.EntitlementSource
import com.pynapli.shared.network.dto.SubmitPlayStoreSubscriptionPurchaseRequestDto
import com.pynapli.shared.network.dto.SubscriptionEntitlementDto
import com.pynapli.shared.network.dto.SubscriptionInventoryResponseDto
import com.pynapli.shared.network.dto.SubscriptionProductId
import com.pynapli.shared.network.dto.SubscriptionPurchaseSyncResponseDto
import com.pynapli.shared.network.dto.SubscriptionPurchaseSyncStatus
import com.pynapli.shared.network.dto.SubscriptionTier
import com.pynapli.shared.network.dto.SubscriptionTierDto
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

class SubscriptionService(
    private val repository: SubscriptionRepository,
    private val clock: Clock = Clock.systemUTC(),
    private val playStorePackageName: String = "com.pynapli.app",
    private val playStorePurchaseVerifier: PlayStorePurchaseVerifier = DisabledPlayStorePurchaseVerifier,
    private val playStoreSyncStaleAfter: Duration = Duration.ofHours(6),
    private val playStoreSyncRenewalLookahead: Duration = Duration.ofHours(24)
) {
    suspend fun getEntitlement(userId: UUID): SubscriptionEntitlementDto {
        val now = clock.instant()
        val own = repository.findBestActiveOwnSubscription(userId, now)?.toEntitlement()
        val linked = repository.findBestActiveLinkedPartnerSubscription(userId, now)?.toEntitlement()
        val stored = repository.findBestActiveStoredEntitlement(userId, now)?.toEntitlement()

        return listOfNotNull(own, stored, linked).maxWithOrNull(entitlementComparator()) ?: freeEntitlement()
    }

    suspend fun listStoredEntitlementsForUser(
        userIdText: String,
        limitText: String?
    ): List<StoredEntitlementRecord> {
        val userId = userIdText.toUuidOrThrow("INVALID_ENTITLEMENT_USER")
        val limit = limitText?.toIntOrNull()?.coerceIn(1, 100) ?: 50
        return repository.listStoredEntitlementsForUser(userId, limit)
    }

    suspend fun grantStoredEntitlement(
        userIdText: String,
        tierText: String?,
        sourceText: String?,
        startsAtText: String?,
        endsAtText: String?,
        actorId: String,
        reason: String
    ): StoredEntitlementRecord {
        val userId = userIdText.toUuidOrThrow("INVALID_ENTITLEMENT_USER")
        val tier = tierText?.trim()?.lowercase()
            ?.takeIf { it in ADMIN_GRANTABLE_TIERS }
            ?: throw SubscriptionException("INVALID_ENTITLEMENT_TIER")
        val source = sourceText?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: EntitlementSource.ADMIN
        if (source !in ADMIN_GRANTABLE_SOURCES) throw SubscriptionException("INVALID_ENTITLEMENT_SOURCE")
        val startsAt = startsAtText?.toInstantOrThrow("INVALID_ENTITLEMENT_PERIOD") ?: clock.instant()
        val endsAt = endsAtText?.toInstantOrThrow("INVALID_ENTITLEMENT_PERIOD")
        if (endsAt != null && endsAt <= startsAt) throw SubscriptionException("INVALID_ENTITLEMENT_PERIOD")
        val normalizedActorId = actorId.validatedAdminActor()
        val normalizedReason = reason.validatedAdminReason()

        return repository.grantStoredEntitlement(
            userId = userId,
            tier = tier,
            source = source,
            startsAt = startsAt,
            endsAt = endsAt,
            actorId = normalizedActorId,
            reason = normalizedReason
        ) ?: throw SubscriptionException("INVALID_ENTITLEMENT_USER")
    }

    suspend fun revokeStoredEntitlement(
        entitlementIdText: String,
        actorId: String,
        reason: String
    ): StoredEntitlementRecord {
        val entitlementId = entitlementIdText.toUuidOrThrow("ENTITLEMENT_NOT_FOUND")
        return repository.revokeStoredEntitlement(
            entitlementId = entitlementId,
            actorId = actorId.validatedAdminActor(),
            reason = reason.validatedAdminReason()
        ) ?: throw SubscriptionException("ENTITLEMENT_NOT_FOUND")
    }

    fun getInventory(): SubscriptionInventoryResponseDto =
        SubscriptionInventoryResponseDto(
            tiers = listOf(
                SubscriptionTierDto(
                    tier = SubscriptionTier.FREE,
                    displayName = "Pynapli Free",
                    monthlyPriceUsdCents = 0,
                    adSupported = true,
                    linkedPartnerIncluded = false,
                    features = listOf(
                        "Basic grid",
                        "Basic swipe",
                        "Grid messaging",
                        "Partner linking",
                        "Private album basics",
                        "Block and report"
                    )
                ),
                SubscriptionTierDto(
                    tier = SubscriptionTier.SOCIAL,
                    displayName = "Pynapli Social",
                    monthlyPriceUsdCents = 499,
                    adSupported = false,
                    linkedPartnerIncluded = true,
                    playStoreProductId = SubscriptionProductId.PLAY_SOCIAL_MONTHLY,
                    features = listOf(
                        "No ads",
                        "Expanded grid",
                        "More swipe likes",
                        "Rewind",
                        "More activity reveal",
                        "Linked partner included"
                    )
                ),
                SubscriptionTierDto(
                    tier = SubscriptionTier.SELECT,
                    displayName = "Pynapli Select",
                    monthlyPriceUsdCents = 999,
                    adSupported = false,
                    linkedPartnerIncluded = true,
                    playStoreProductId = SubscriptionProductId.PLAY_SELECT_MONTHLY,
                    features = listOf(
                        "Full activity reveal",
                        "Advanced filters",
                        "Incognito",
                        "Travel mode",
                        "Intro Notes",
                        "Monthly Boost",
                        "Linked partner included"
                    )
                )
            )
        )

    suspend fun recordPlayStoreSubscriptionPurchase(
        userId: UUID,
        request: SubmitPlayStoreSubscriptionPurchaseRequestDto
    ): SubscriptionPurchaseSyncResponseDto {
        val productId = request.productId.trim()
        val purchaseToken = request.purchaseToken.trim()
        val packageName = request.packageName.trim()
        if (productId.isEmpty()) throw SubscriptionException("INVALID_SUBSCRIPTION_PRODUCT")
        if (purchaseToken.length !in 16..4096) throw SubscriptionException("INVALID_PURCHASE_TOKEN")
        if (packageName != playStorePackageName) throw SubscriptionException("INVALID_PURCHASE_PACKAGE")

        val tier = tierForPlayStoreProduct(productId) ?: throw SubscriptionException("INVALID_SUBSCRIPTION_PRODUCT")
        val now = clock.instant()
        return when (
            val verification = playStorePurchaseVerifier.verifySubscriptionPurchase(
                packageName = packageName,
                productId = productId,
                purchaseToken = purchaseToken
            )
        ) {
            is PlayStorePurchaseVerificationResult.Invalid -> throw SubscriptionException(verification.code)
            PlayStorePurchaseVerificationResult.VerificationUnavailable -> {
                repository.upsertPendingPlayStoreSubscriptionPurchase(
                    userId = userId,
                    productId = productId,
                    purchaseToken = purchaseToken,
                    tier = tier,
                    now = now
                )
                pendingVerificationResponse(userId)
            }
            is PlayStorePurchaseVerificationResult.Verified -> {
                val subscription = applyVerifiedPlayStoreSubscription(
                    userId = userId,
                    tier = tier,
                    purchase = verification.purchase,
                    verifiedAt = now
                )

                if (subscription.status in ACTIVE_SUBSCRIPTION_STATUSES) {
                    SubscriptionPurchaseSyncResponseDto(
                        status = SubscriptionPurchaseSyncStatus.ACTIVE,
                        entitlement = getEntitlement(userId)
                    )
                } else {
                    pendingVerificationResponse(userId)
                }
            }
        }
    }

    suspend fun syncDuePlayStoreSubscriptions(limit: Int = PLAY_STORE_SYNC_DEFAULT_LIMIT): PlayStoreSubscriptionSyncSummary {
        val now = clock.instant()
        val normalizedLimit = limit.coerceIn(1, PLAY_STORE_SYNC_MAX_LIMIT)
        val candidates = repository.listPlayStoreSubscriptionsDueForVerification(
            staleBefore = now.minus(playStoreSyncStaleAfter),
            renewalWindowEnd = now.plus(playStoreSyncRenewalLookahead),
            limit = normalizedLimit
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
                val verification = playStorePurchaseVerifier.verifySubscriptionPurchase(
                    packageName = playStorePackageName,
                    productId = candidate.productId,
                    purchaseToken = candidate.purchaseToken
                )
            ) {
                is PlayStorePurchaseVerificationResult.Invalid -> {
                    invalid++
                    revoked++
                    repository.upsertVerifiedPlayStoreSubscriptionPurchase(
                        userId = candidate.userId,
                        productId = candidate.productId,
                        purchaseToken = candidate.purchaseToken,
                        orderId = null,
                        tier = candidate.tier,
                        status = "revoked",
                        externalStatus = verification.code,
                        acknowledgementState = null,
                        acknowledgedAt = null,
                        currentPeriodStart = candidate.currentPeriodStart,
                        currentPeriodEnd = candidate.currentPeriodEnd,
                        verifiedAt = now
                    )
                }
                PlayStorePurchaseVerificationResult.VerificationUnavailable -> unavailable++
                is PlayStorePurchaseVerificationResult.Verified -> {
                    verified++
                    val subscription = applyVerifiedPlayStoreSubscription(
                        userId = candidate.userId,
                        tier = candidate.tier,
                        purchase = verification.purchase,
                        verifiedAt = now
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
            unavailable = unavailable
        )
    }

    suspend fun handlePlayStoreRealTimeDeveloperNotification(
        envelope: PlayStoreRtdnEnvelope
    ): PlayStoreRtdnProcessingResult {
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
            return PlayStoreRtdnProcessingResult(
                status = "duplicate",
                eventType = envelope.eventType,
                messageId = envelope.messageId,
                purchaseToken = envelope.purchaseToken,
                productId = envelope.productId
            )
        }

        fun finish(
            status: String,
            errorCode: String? = null,
            productId: String? = envelope.productId,
            subscriptionStatus: String? = null
        ): PlayStoreRtdnProcessingResult {
            return PlayStoreRtdnProcessingResult(
                status = status,
                eventType = envelope.eventType,
                messageId = envelope.messageId,
                purchaseToken = envelope.purchaseToken,
                productId = productId,
                subscriptionStatus = subscriptionStatus,
                errorCode = errorCode
            )
        }

        if (notification.packageName != playStorePackageName) {
            repository.updatePlayStoreRtdnEventStatus(
                messageId = envelope.messageId,
                processingStatus = "ignored",
                errorCode = "INVALID_PURCHASE_PACKAGE"
            )
            return finish(status = "ignored", errorCode = "INVALID_PURCHASE_PACKAGE")
        }

        return when {
            notification.testNotification != null -> {
                repository.updatePlayStoreRtdnEventStatus(envelope.messageId, "processed")
                finish(status = "processed")
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
                finish(status = "ignored", errorCode = "UNSUPPORTED_RTDN_EVENT")
            }
        }
    }

    suspend fun getPolicy(userId: UUID): EntitlementPolicy =
        getEntitlement(userId).toPolicy()

    suspend fun consumeIntroNoteInventory(userId: UUID): Boolean =
        repository.consumeIntroNoteInventory(userId, clock.instant())

    suspend fun consumePineappleLikeInventory(userId: UUID): Boolean =
        repository.consumePineappleLikeInventory(userId, clock.instant())

    private fun SubscriptionRecord.toEntitlement(): SubscriptionEntitlementDto =
        paidEntitlement(
            tier = tier,
            source = EntitlementSource.OWN_SUBSCRIPTION,
            sourceSubscriptionId = subscriptionId,
            sourceUserId = userId,
            linkedPartnerUserId = null,
            startsAt = currentPeriodStart,
            endsAt = currentPeriodEnd
        )

    private fun LinkedPartnerSubscriptionRecord.toEntitlement(): SubscriptionEntitlementDto =
        paidEntitlement(
            tier = subscription.tier,
            source = EntitlementSource.LINKED_PARTNER_BENEFIT,
            sourceSubscriptionId = subscription.subscriptionId,
            sourceUserId = linkedPartnerUserId,
            linkedPartnerUserId = linkedPartnerUserId,
            startsAt = subscription.currentPeriodStart,
            endsAt = subscription.currentPeriodEnd
        )

    private fun StoredEntitlementRecord.toEntitlement(): SubscriptionEntitlementDto =
        paidEntitlement(
            tier = tier,
            source = source,
            sourceSubscriptionId = sourceId,
            sourceUserId = userId,
            linkedPartnerUserId = null,
            startsAt = startsAt,
            endsAt = endsAt
        )

    private fun paidEntitlement(
        tier: String,
        source: String,
        sourceSubscriptionId: UUID?,
        sourceUserId: UUID,
        linkedPartnerUserId: UUID?,
        startsAt: Instant,
        endsAt: Instant?
    ): SubscriptionEntitlementDto =
        SubscriptionEntitlementDto(
            tier = tier,
            source = source,
            displayName = displayNameFor(tier),
            adSupported = false,
            linkedPartnerIncluded = true,
            sourceSubscriptionId = sourceSubscriptionId?.toString(),
            sourceUserId = sourceUserId.toString(),
            linkedPartnerUserId = linkedPartnerUserId?.toString(),
            startsAt = startsAt.toString(),
            endsAt = endsAt?.toString()
        )

    private fun freeEntitlement(): SubscriptionEntitlementDto =
        SubscriptionEntitlementDto(
            tier = SubscriptionTier.FREE,
            source = EntitlementSource.FREE,
            displayName = "Pynapli Free",
            adSupported = true,
            linkedPartnerIncluded = false
        )

    private fun entitlementComparator(): Comparator<SubscriptionEntitlementDto> =
        compareBy<SubscriptionEntitlementDto> { tierRank(it.tier) }
            .thenBy { sourcePriority(it.source) }
            .thenBy { it.endsAt ?: "" }

    private fun sourcePriority(source: String): Int =
        when (source) {
            EntitlementSource.OWN_SUBSCRIPTION -> 3
            EntitlementSource.ADMIN -> 2
            EntitlementSource.PROMO -> 1
            EntitlementSource.LINKED_PARTNER_BENEFIT -> 0
            else -> -1
        }

    private fun tierRank(tier: String): Int =
        when (tier) {
            SubscriptionTier.SELECT -> 2
            SubscriptionTier.SOCIAL -> 1
            else -> 0
        }

    private fun displayNameFor(tier: String): String =
        when (tier) {
            SubscriptionTier.SELECT -> "Pynapli Select"
            SubscriptionTier.SOCIAL -> "Pynapli Social"
            else -> "Pynapli Free"
        }

    private fun tierForPlayStoreProduct(productId: String): String? =
        when (productId) {
            SubscriptionProductId.PLAY_SOCIAL_MONTHLY -> SubscriptionTier.SOCIAL
            SubscriptionProductId.PLAY_SELECT_MONTHLY -> SubscriptionTier.SELECT
            else -> null
        }

    private suspend fun pendingVerificationResponse(userId: UUID): SubscriptionPurchaseSyncResponseDto =
        SubscriptionPurchaseSyncResponseDto(
            status = SubscriptionPurchaseSyncStatus.PENDING_VERIFICATION,
            entitlement = getEntitlement(userId)
        )

    private suspend fun processSubscriptionRtdn(
        envelope: PlayStoreRtdnEnvelope,
        notification: PlayStoreSubscriptionNotificationDto
    ): PlayStoreRtdnProcessingResult {
        val purchaseToken = notification.purchaseToken?.trim()?.takeIf { it.length in 16..4096 }
        if (purchaseToken == null) {
            repository.updatePlayStoreRtdnEventStatus(envelope.messageId, "ignored", "INVALID_PURCHASE_TOKEN")
            return PlayStoreRtdnProcessingResult(
                status = "ignored",
                eventType = envelope.eventType,
                messageId = envelope.messageId,
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
                if (stored != null) {
                    repository.upsertVerifiedPlayStoreSubscriptionPurchase(
                        userId = stored.userId,
                        productId = stored.productId,
                        purchaseToken = purchaseToken,
                        orderId = null,
                        tier = stored.tier,
                        status = "revoked",
                        externalStatus = verification.code,
                        acknowledgementState = null,
                        acknowledgedAt = null,
                        currentPeriodStart = stored.currentPeriodStart,
                        currentPeriodEnd = stored.currentPeriodEnd,
                        verifiedAt = clock.instant()
                    )
                    repository.updatePlayStoreRtdnEventStatus(envelope.messageId, "processed", verification.code)
                    PlayStoreRtdnProcessingResult(
                        status = "processed",
                        eventType = envelope.eventType,
                        messageId = envelope.messageId,
                        purchaseToken = purchaseToken,
                        productId = stored.productId,
                        subscriptionStatus = "revoked",
                        errorCode = verification.code
                    )
                } else {
                    repository.updatePlayStoreRtdnEventStatus(envelope.messageId, "ignored", verification.code)
                    PlayStoreRtdnProcessingResult(
                        status = "ignored",
                        eventType = envelope.eventType,
                        messageId = envelope.messageId,
                        purchaseToken = purchaseToken,
                        productId = productId,
                        errorCode = verification.code
                    )
                }
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
                val purchase = verification.purchase
                val tier = tierForPlayStoreProduct(purchase.productId)
                    ?: stored?.tier
                    ?: run {
                        repository.updatePlayStoreRtdnEventStatus(
                            envelope.messageId,
                            "ignored",
                            "INVALID_SUBSCRIPTION_PRODUCT"
                        )
                        return PlayStoreRtdnProcessingResult(
                            status = "ignored",
                            eventType = envelope.eventType,
                            messageId = envelope.messageId,
                            purchaseToken = purchaseToken,
                            productId = purchase.productId,
                            errorCode = "INVALID_SUBSCRIPTION_PRODUCT"
                        )
                    }
                val userId = stored?.userId ?: run {
                    repository.updatePlayStoreRtdnEventStatus(envelope.messageId, "ignored", "PURCHASE_NOT_LINKED")
                    return PlayStoreRtdnProcessingResult(
                        status = "ignored",
                        eventType = envelope.eventType,
                        messageId = envelope.messageId,
                        purchaseToken = purchaseToken,
                        productId = purchase.productId,
                        errorCode = "PURCHASE_NOT_LINKED"
                    )
                }
                val subscription = applyVerifiedPlayStoreSubscription(
                    userId = userId,
                    tier = tier,
                    purchase = purchase,
                    verifiedAt = clock.instant()
                )
                repository.updatePlayStoreRtdnEventStatus(envelope.messageId, "processed")
                PlayStoreRtdnProcessingResult(
                    status = "processed",
                    eventType = envelope.eventType,
                    messageId = envelope.messageId,
                    purchaseToken = purchaseToken,
                    productId = purchase.productId,
                    subscriptionStatus = subscription.status
                )
            }
        }
    }

    private suspend fun processVoidedPurchaseRtdn(
        envelope: PlayStoreRtdnEnvelope,
        notification: PlayStoreVoidedPurchaseNotificationDto
    ): PlayStoreRtdnProcessingResult {
        val purchaseToken = notification.purchaseToken?.trim()?.takeIf { it.length in 16..4096 }
        if (purchaseToken == null) {
            repository.updatePlayStoreRtdnEventStatus(envelope.messageId, "ignored", "INVALID_PURCHASE_TOKEN")
            return PlayStoreRtdnProcessingResult(
                status = "ignored",
                eventType = envelope.eventType,
                messageId = envelope.messageId,
                purchaseToken = notification.purchaseToken,
                errorCode = "INVALID_PURCHASE_TOKEN"
            )
        }
        val stored = repository.findPlayStoreSubscriptionByPurchaseToken(purchaseToken)
        if (stored == null || notification.productType != PLAY_STORE_PRODUCT_TYPE_SUBSCRIPTION) {
            repository.updatePlayStoreRtdnEventStatus(envelope.messageId, "ignored", "PURCHASE_NOT_LINKED")
            return PlayStoreRtdnProcessingResult(
                status = "ignored",
                eventType = envelope.eventType,
                messageId = envelope.messageId,
                purchaseToken = purchaseToken,
                errorCode = "PURCHASE_NOT_LINKED"
            )
        }
        repository.upsertVerifiedPlayStoreSubscriptionPurchase(
            userId = stored.userId,
            productId = stored.productId,
            purchaseToken = purchaseToken,
            orderId = notification.orderId,
            tier = stored.tier,
            status = "revoked",
            externalStatus = "VOIDED_PURCHASE_REFUND_TYPE_${notification.refundType ?: 0}",
            acknowledgementState = null,
            acknowledgedAt = null,
            currentPeriodStart = stored.currentPeriodStart,
            currentPeriodEnd = stored.currentPeriodEnd,
            verifiedAt = clock.instant()
        )
        repository.updatePlayStoreRtdnEventStatus(envelope.messageId, "processed")
        return PlayStoreRtdnProcessingResult(
            status = "processed",
            eventType = envelope.eventType,
            messageId = envelope.messageId,
            purchaseToken = purchaseToken,
            productId = stored.productId,
            subscriptionStatus = "revoked"
        )
    }

    private suspend fun applyVerifiedPlayStoreSubscription(
        userId: UUID,
        tier: String,
        purchase: VerifiedPlayStoreSubscriptionPurchase,
        verifiedAt: Instant
    ): SubscriptionRecord {
        val subscriptionStatus = purchase.toSubscriptionStatus(verifiedAt)
        return repository.upsertVerifiedPlayStoreSubscriptionPurchase(
            userId = userId,
            productId = purchase.productId,
            purchaseToken = purchase.purchaseToken,
            orderId = purchase.latestOrderId,
            tier = tier,
            status = subscriptionStatus,
            externalStatus = purchase.subscriptionState,
            acknowledgementState = purchase.acknowledgementState,
            acknowledgedAt = if (purchase.isAcknowledged()) verifiedAt else null,
            currentPeriodStart = purchase.currentPeriodStart,
            currentPeriodEnd = purchase.currentPeriodEnd,
            verifiedAt = verifiedAt
        )
    }

    private fun VerifiedPlayStoreSubscriptionPurchase.toSubscriptionStatus(now: Instant): String {
        if (currentPeriodEnd <= now) return "expired"
        return when (subscriptionState) {
            "SUBSCRIPTION_STATE_ACTIVE",
            "SUBSCRIPTION_STATE_CANCELED" -> "active"
            "SUBSCRIPTION_STATE_IN_GRACE_PERIOD" -> "grace"
            "SUBSCRIPTION_STATE_EXPIRED" -> "expired"
            "SUBSCRIPTION_STATE_REVOKED" -> "revoked"
            else -> "pending"
        }
    }

    private fun VerifiedPlayStoreSubscriptionPurchase.isAcknowledged(): Boolean =
        acknowledgementState == "ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED"

    companion object {
        private val ACTIVE_SUBSCRIPTION_STATUSES = setOf("active", "grace")
        private val ADMIN_GRANTABLE_TIERS = setOf(SubscriptionTier.SOCIAL, SubscriptionTier.SELECT)
        private val ADMIN_GRANTABLE_SOURCES = setOf(EntitlementSource.ADMIN, EntitlementSource.PROMO)
        private const val PLAY_STORE_SYNC_DEFAULT_LIMIT = 100
        private const val PLAY_STORE_SYNC_MAX_LIMIT = 500
        private const val PLAY_STORE_PRODUCT_TYPE_SUBSCRIPTION = 1
    }
}

class SubscriptionException(val code: String) : RuntimeException(code)

data class PlayStoreSubscriptionSyncSummary(
    val scanned: Int,
    val verified: Int,
    val active: Int,
    val grace: Int,
    val pending: Int,
    val expired: Int,
    val revoked: Int,
    val invalid: Int,
    val unavailable: Int
)

data class EntitlementPolicy(
    val tier: String,
    val adSupported: Boolean,
    val activityRevealLimit: Int,
    val advancedFiltersAllowed: Boolean,
    val introNoteInventoryRequired: Boolean
) {
    val fullActivityReveal: Boolean
        get() = activityRevealLimit == Int.MAX_VALUE

    companion object {
        fun unrestricted(): EntitlementPolicy =
            EntitlementPolicy(
                tier = SubscriptionTier.SELECT,
                adSupported = false,
                activityRevealLimit = Int.MAX_VALUE,
                advancedFiltersAllowed = true,
                introNoteInventoryRequired = true
            )
    }
}

fun SubscriptionEntitlementDto.toPolicy(): EntitlementPolicy =
    when (tier) {
        SubscriptionTier.SELECT -> EntitlementPolicy(
            tier = tier,
            adSupported = adSupported,
            activityRevealLimit = Int.MAX_VALUE,
            advancedFiltersAllowed = true,
            introNoteInventoryRequired = true
        )
        SubscriptionTier.SOCIAL -> EntitlementPolicy(
            tier = tier,
            adSupported = adSupported,
            activityRevealLimit = 10,
            advancedFiltersAllowed = true,
            introNoteInventoryRequired = true
        )
        else -> EntitlementPolicy(
            tier = SubscriptionTier.FREE,
            adSupported = true,
            activityRevealLimit = 1,
            advancedFiltersAllowed = false,
            introNoteInventoryRequired = true
        )
    }

private fun String.toUuidOrThrow(code: String): UUID =
    runCatching { UUID.fromString(trim()) }
        .getOrElse { throw SubscriptionException(code) }

private fun String.toInstantOrThrow(code: String): Instant =
    runCatching { Instant.parse(trim()) }
        .getOrElse { throw SubscriptionException(code) }

private fun String.validatedAdminActor(): String =
    trim().takeIf { it.length in 2..120 }
        ?: throw SubscriptionException("INVALID_ADMIN_ACTOR")

private fun String.validatedAdminReason(): String =
    trim().takeIf { it.length in 2..500 }
        ?: throw SubscriptionException("ADMIN_REASON_REQUIRED")
