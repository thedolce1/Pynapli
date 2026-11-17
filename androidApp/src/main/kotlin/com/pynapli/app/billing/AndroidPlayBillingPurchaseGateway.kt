package com.pynapli.app.billing

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.pynapli.app.store.PurchaseLock
import com.pynapli.app.store.StorePurchaseGateway
import com.pynapli.app.store.StorePurchaseResult
import com.pynapli.shared.core.result.AppError
import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.PynapliApiClient
import com.pynapli.shared.network.dto.PremiumProductDto
import com.pynapli.shared.network.dto.SubmitPlayStoreProductPurchaseRequestDto
import com.pynapli.shared.network.dto.SubmitPlayStoreSubscriptionPurchaseRequestDto
import com.pynapli.shared.network.dto.SubscriptionProductId
import com.pynapli.shared.network.dto.SubscriptionPurchaseSyncStatus
import com.pynapli.shared.network.dto.SubscriptionTier
import com.pynapli.shared.network.dto.SubscriptionTierDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference
import kotlin.coroutines.resume

private data class PendingPurchase(
    val tier: String,
    val productId: String,
)

private typealias PurchaseClaim = PurchaseLock.Claim<PendingPurchase, AppResult<StorePurchaseResult>>

class AndroidPlayBillingPurchaseGateway(
    context: Context,
    private val apiClient: PynapliApiClient,
    private val packageNameProvider: () -> String,
) : StorePurchaseGateway,
    PurchasesUpdatedListener {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var activityRef = WeakReference<Activity?>(null)
    private val purchaseLock = PurchaseLock<PendingPurchase, AppResult<StorePurchaseResult>>()

    private val billingClient: BillingClient =
        BillingClient
            .newBuilder(appContext)
            .setListener(this)
            .enablePendingPurchases(
                PendingPurchasesParams
                    .newBuilder()
                    .enableOneTimeProducts()
                    .build(),
            ).enableAutoServiceReconnection()
            .build()

    fun attachActivity(activity: Activity) {
        activityRef = WeakReference(activity)
    }

    fun detachActivity(activity: Activity) {
        if (activityRef.get() === activity) {
            activityRef = WeakReference(null)
        }
    }

    override suspend fun purchase(tier: SubscriptionTierDto): AppResult<StorePurchaseResult> {
        val productId =
            tier.playStoreProductId
                ?: return billingFailure("BILLING_PRODUCT_UNAVAILABLE", "This subscription is not available yet.")
        if (tier.tier == SubscriptionTier.FREE) {
            return billingFailure("BILLING_FREE_TIER", "Free does not need checkout.")
        }
        val activity =
            activityRef.get()
                ?: return billingFailure("BILLING_ACTIVITY_UNAVAILABLE", "Purchases are not available right now.")
        // Claimed before the first suspension, so a second tap can't slip in while this one connects.
        val claim = purchaseLock.tryClaim(PendingPurchase(tier = tier.tier, productId = productId)) ?: return purchaseInProgress()
        try {
            return launchSubscriptionCheckout(claim, activity, productId)
        } finally {
            // Every exit that didn't complete the claim (an early failure, or the caller cancelled
            // while the sheet was open) frees it; a later Play callback goes to the no-pending path.
            purchaseLock.release(claim)
        }
    }

    private suspend fun launchSubscriptionCheckout(
        claim: PurchaseClaim,
        activity: Activity,
        productId: String,
    ): AppResult<StorePurchaseResult> {
        val connection = ensureConnected()
        if (connection.responseCode != BillingClient.BillingResponseCode.OK) {
            return billingFailure("BILLING_UNAVAILABLE", connection.debugMessage.ifBlank { "Google Play Billing is unavailable." })
        }

        val productDetailsResult = queryProductDetails(productId)
        if (productDetailsResult is AppResult.Failure) return productDetailsResult
        val productDetails = (productDetailsResult as AppResult.Success).value
        val offerToken =
            productDetails.subscriptionOfferDetails
                ?.firstOrNull()
                ?.offerToken
                ?: return billingFailure("BILLING_OFFER_UNAVAILABLE", "This subscription offer is not available yet.")

        val params =
            BillingFlowParams
                .newBuilder()
                .setProductDetailsParamsList(
                    listOf(
                        BillingFlowParams.ProductDetailsParams
                            .newBuilder()
                            .setProductDetails(productDetails)
                            .setOfferToken(offerToken)
                            .build(),
                    ),
                ).build()

        return launchCheckout(claim, activity, params)
    }

    /** Opens Play's sheet for [claim] and waits for its callback. */
    private suspend fun launchCheckout(
        claim: PurchaseClaim,
        activity: Activity,
        params: BillingFlowParams,
    ): AppResult<StorePurchaseResult> {
        // From here Play's callback belongs to this claim. Set before launching: on the main thread
        // the callback can't run until this coroutine suspends.
        claim.launched = true
        val launchResult =
            withContext(Dispatchers.Main.immediate) {
                billingClient.launchBillingFlow(activity, params)
            }
        if (launchResult.responseCode != BillingClient.BillingResponseCode.OK) {
            return billingFailure(
                code = "BILLING_LAUNCH_FAILED",
                message = launchResult.debugMessage.ifBlank { "Google Play checkout could not be opened." },
            )
        }
        return claim.result.await()
    }

    override suspend fun purchaseProduct(product: PremiumProductDto): AppResult<StorePurchaseResult> {
        val productId =
            product.playStoreProductId
                ?: return billingFailure("BILLING_PRODUCT_UNAVAILABLE", "This item is not available in Google Play yet.")
        val activity =
            activityRef.get()
                ?: return billingFailure("BILLING_ACTIVITY_UNAVAILABLE", "Purchases are not available right now.")
        val claim = purchaseLock.tryClaim(PendingPurchase(tier = ONE_TIME_PURCHASE, productId = productId)) ?: return purchaseInProgress()
        try {
            return launchProductCheckout(claim, activity, productId)
        } finally {
            purchaseLock.release(claim)
        }
    }

    private suspend fun launchProductCheckout(
        claim: PurchaseClaim,
        activity: Activity,
        productId: String,
    ): AppResult<StorePurchaseResult> {
        val connection = ensureConnected()
        if (connection.responseCode != BillingClient.BillingResponseCode.OK) {
            return billingFailure("BILLING_UNAVAILABLE", connection.debugMessage.ifBlank { "Google Play Billing is unavailable." })
        }
        val detailsResult = queryProductDetails(productId, BillingClient.ProductType.INAPP)
        if (detailsResult is AppResult.Failure) return detailsResult
        val productDetails = (detailsResult as AppResult.Success).value
        if (productDetails.oneTimePurchaseOfferDetails == null) {
            return billingFailure("BILLING_PRODUCT_UNAVAILABLE", "This item is not available in Google Play yet.")
        }

        val params =
            BillingFlowParams
                .newBuilder()
                .setProductDetailsParamsList(
                    listOf(
                        BillingFlowParams.ProductDetailsParams
                            .newBuilder()
                            .setProductDetails(productDetails)
                            .build(),
                    ),
                ).build()
        return launchCheckout(claim, activity, params)
    }

    override suspend fun restorePurchases(): AppResult<StorePurchaseResult> {
        // Holds the same lock as checkout so a restore and a purchase never interleave. Never
        // launched, so a Play callback arriving meanwhile takes the no-pending path.
        val claim = purchaseLock.tryClaim(PendingPurchase(tier = RESTORE, productId = "")) ?: return purchaseInProgress()
        try {
            return restoreSubscriptions()
        } finally {
            purchaseLock.release(claim)
        }
    }

    private suspend fun restoreSubscriptions(): AppResult<StorePurchaseResult> {
        val connection = ensureConnected()
        if (connection.responseCode != BillingClient.BillingResponseCode.OK) {
            return billingFailure(
                code = "BILLING_UNAVAILABLE",
                message = connection.debugMessage.ifBlank { "Google Play Billing is unavailable." },
            )
        }

        val purchasesResult = querySubscriptionPurchases()
        if (purchasesResult is AppResult.Failure) return purchasesResult

        val purchases = (purchasesResult as AppResult.Success).value
        val purchasedSubscriptions = purchases.filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }
        if (purchasedSubscriptions.isEmpty()) {
            val hasPending = purchases.any { it.purchaseState == Purchase.PurchaseState.PENDING }
            return AppResult.Success(
                StorePurchaseResult(
                    message =
                        if (hasPending) {
                            "Purchase is pending in Google Play."
                        } else {
                            "No active Google Play subscriptions found."
                        },
                    refreshEntitlement = !hasPending,
                ),
            )
        }

        var restoredCount = 0
        for (purchase in purchasedSubscriptions) {
            when (val result = handlePurchase(purchase)) {
                is AppResult.Success -> restoredCount += 1
                is AppResult.Failure -> return result
            }
        }

        return AppResult.Success(
            StorePurchaseResult(
                message =
                    if (restoredCount == 1) {
                        "Subscription restored."
                    } else {
                        "Subscriptions restored."
                    },
                refreshEntitlement = true,
            ),
        )
    }

    override suspend fun syncUnfinishedPurchases(): Boolean {
        // Check-only, not a claim: a Buy tap during this quiet sync shouldn't be refused.
        if (purchaseLock.isHeld) return false
        if (ensureConnected().responseCode != BillingClient.BillingResponseCode.OK) return false
        val subscriptions = (querySubscriptionPurchases() as? AppResult.Success)?.value.orEmpty()
        val oneTimeItems = (querySubscriptionPurchases(BillingClient.ProductType.INAPP) as? AppResult.Success)?.value.orEmpty()
        var synced = false
        subscriptions
            .filter { it.purchaseState == Purchase.PurchaseState.PURCHASED && !it.isAcknowledged }
            .forEach { purchase -> if (handlePurchase(purchase) is AppResult.Success) synced = true }
        // An unconsumed one-time item was paid for but never credited (or credited but not consumed).
        oneTimeItems
            .filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }
            .forEach { purchase -> if (handlePurchase(purchase) is AppResult.Success) synced = true }
        return synced
    }

    override fun onPurchasesUpdated(
        billingResult: BillingResult,
        purchases: MutableList<Purchase>?,
    ) {
        // Only a claim whose sheet is open owns this callback; a restore, or a checkout still
        // connecting, doesn't.
        val pending = purchaseLock.current?.takeIf { it.launched }
        if (pending == null) {
            // Play delivers purchases outside a checkout too (a pending payment that cleared, a
            // purchase finished after the app was closed, or one whose caller left before the sheet
            // closed). Confirm them instead of dropping them.
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                confirmOutsideCheckout(purchases.orEmpty())
            }
            return
        }
        when (billingResult.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                val productId = pending.payload.productId
                val (mine, others) = purchases.orEmpty().partition { purchase -> productId in purchase.products }
                confirmOutsideCheckout(others)
                val purchase = mine.firstOrNull()
                if (purchase == null) {
                    pending.completeFailure("BILLING_PURCHASE_NOT_FOUND", "Purchase details were not returned.")
                    return
                }
                scope.launch {
                    pending.complete(handlePurchase(purchase))
                }
            }

            BillingClient.BillingResponseCode.USER_CANCELED -> {
                pending.completeFailure("BILLING_USER_CANCELED", "Purchase canceled.")
            }

            else -> {
                pending.completeFailure(
                    code = "BILLING_PURCHASE_FAILED",
                    message = billingResult.debugMessage.ifBlank { "Purchase could not be completed." },
                )
            }
        }
    }

    private fun confirmOutsideCheckout(purchases: List<Purchase>) {
        purchases
            .filter { it.purchaseState == Purchase.PurchaseState.PURCHASED && !it.isAcknowledged }
            .forEach { purchase -> scope.launch { handlePurchase(purchase) } }
    }

    private suspend fun handlePurchase(purchase: Purchase): AppResult<StorePurchaseResult> {
        if (purchase.purchaseState == Purchase.PurchaseState.PENDING) {
            return AppResult.Success(
                StorePurchaseResult(
                    message = "Purchase is pending in Google Play.",
                    refreshEntitlement = false,
                ),
            )
        }
        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) {
            return billingFailure("BILLING_PURCHASE_NOT_COMPLETED", "Purchase was not completed.")
        }

        val productId =
            purchase.products.firstOrNull()
                ?: return billingFailure("BILLING_PURCHASE_NOT_FOUND", "Purchase details were not returned.")
        if (productId in ONE_TIME_PRODUCT_IDS) return handleOneTimePurchase(purchase, productId)
        return when (
            val result =
                apiClient.submitPlayStoreSubscriptionPurchase(
                    SubmitPlayStoreSubscriptionPurchaseRequestDto(
                        productId = productId,
                        purchaseToken = purchase.purchaseToken,
                        packageName = packageNameProvider(),
                    ),
                )
        ) {
            is AppResult.Success ->
                AppResult.Success(
                    StorePurchaseResult(
                        message =
                            when (result.value.status) {
                                SubscriptionPurchaseSyncStatus.ACTIVE,
                                SubscriptionPurchaseSyncStatus.ALREADY_ACTIVE,
                                -> "Subscription active."
                                else -> "Purchase received. Verification is pending."
                            },
                        refreshEntitlement = true,
                    ),
                )

            is AppResult.Failure -> result
        }
    }

    private suspend fun handleOneTimePurchase(
        purchase: Purchase,
        productId: String,
    ): AppResult<StorePurchaseResult> =
        when (
            val result =
                apiClient.submitPlayStoreProductPurchase(
                    SubmitPlayStoreProductPurchaseRequestDto(
                        productId = productId,
                        purchaseToken = purchase.purchaseToken,
                        packageName = packageNameProvider(),
                    ),
                )
        ) {
            is AppResult.Success -> {
                if (result.value.status == "pending_verification") {
                    AppResult.Success(StorePurchaseResult(message = "Payment pending in Google Play.", refreshEntitlement = false))
                } else {
                    // Credited (now or before): consume so it can be bought again. A failed consume
                    // is retried by the next sync, and the server won't credit the token twice.
                    consume(purchase.purchaseToken)
                    AppResult.Success(StorePurchaseResult(message = "Added to your balance.", refreshEntitlement = true))
                }
            }
            is AppResult.Failure -> result
        }

    private suspend fun consume(purchaseToken: String): Boolean =
        suspendCancellableCoroutine { continuation ->
            billingClient.consumeAsync(ConsumeParams.newBuilder().setPurchaseToken(purchaseToken).build()) { result, _ ->
                continuation.resume(result.responseCode == BillingClient.BillingResponseCode.OK)
            }
        }

    private suspend fun ensureConnected(): BillingResult {
        if (billingClient.isReady) {
            return BillingResult
                .newBuilder()
                .setResponseCode(BillingClient.BillingResponseCode.OK)
                .build()
        }

        return suspendCancellableCoroutine { continuation ->
            billingClient.startConnection(
                object : BillingClientStateListener {
                    override fun onBillingSetupFinished(billingResult: BillingResult) {
                        continuation.resume(billingResult)
                    }

                    override fun onBillingServiceDisconnected() {
                        if (continuation.isActive) {
                            continuation.resume(
                                BillingResult
                                    .newBuilder()
                                    .setResponseCode(BillingClient.BillingResponseCode.SERVICE_DISCONNECTED)
                                    .setDebugMessage("Google Play Billing disconnected.")
                                    .build(),
                            )
                        }
                    }
                },
            )
        }
    }

    /**
     * Play's own formatted price for each subscription's base plan ("CA$12.99"), so people see their
     * currency and the price Play will charge. Empty when Play can't answer; the Store then falls
     * back to the catalog's USD price.
     */
    override suspend fun localizedPrices(productIds: List<String>): Map<String, String> {
        if (productIds.isEmpty()) return emptyMap()
        if (ensureConnected().responseCode != BillingClient.BillingResponseCode.OK) return emptyMap()
        return suspendCancellableCoroutine { continuation ->
            val query =
                QueryProductDetailsParams
                    .newBuilder()
                    .setProductList(
                        productIds.map { id ->
                            QueryProductDetailsParams.Product
                                .newBuilder()
                                .setProductId(id)
                                .setProductType(BillingClient.ProductType.SUBS)
                                .build()
                        },
                    ).build()
            billingClient.queryProductDetailsAsync(query) { billingResult, result ->
                if (billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
                    continuation.resume(emptyMap())
                    return@queryProductDetailsAsync
                }
                val prices =
                    result.productDetailsList
                        .mapNotNull { details ->
                            details.subscriptionOfferDetails
                                ?.firstOrNull { it.offerId == null }
                                ?.pricingPhases
                                ?.pricingPhaseList
                                ?.lastOrNull()
                                ?.formattedPrice
                                ?.let { price -> details.productId to price }
                        }.toMap()
                continuation.resume(prices)
            }
        }
    }

    private suspend fun queryProductDetails(
        productId: String,
        productType: String = BillingClient.ProductType.SUBS,
    ): AppResult<ProductDetails> =
        suspendCancellableCoroutine { continuation ->
            val query =
                QueryProductDetailsParams
                    .newBuilder()
                    .setProductList(
                        listOf(
                            QueryProductDetailsParams.Product
                                .newBuilder()
                                .setProductId(productId)
                                .setProductType(productType)
                                .build(),
                        ),
                    ).build()

            billingClient.queryProductDetailsAsync(query) { billingResult, result ->
                if (billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
                    continuation.resume(
                        billingFailure(
                            code = "BILLING_PRODUCT_QUERY_FAILED",
                            message = billingResult.debugMessage.ifBlank { "Subscription details could not be loaded." },
                        ),
                    )
                    return@queryProductDetailsAsync
                }
                val productDetails = result.productDetailsList.firstOrNull()
                continuation.resume(
                    if (productDetails == null) {
                        billingFailure("BILLING_PRODUCT_UNAVAILABLE", "This subscription is not available yet.")
                    } else {
                        AppResult.Success(productDetails)
                    },
                )
            }
        }

    private suspend fun querySubscriptionPurchases(productType: String = BillingClient.ProductType.SUBS): AppResult<List<Purchase>> =
        suspendCancellableCoroutine { continuation ->
            val query =
                QueryPurchasesParams
                    .newBuilder()
                    .setProductType(productType)
                    .build()

            billingClient.queryPurchasesAsync(query) { billingResult, purchases ->
                if (billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
                    continuation.resume(
                        billingFailure(
                            code = "BILLING_RESTORE_FAILED",
                            message =
                                billingResult.debugMessage.ifBlank {
                                    "Google Play subscriptions could not be restored."
                                },
                        ),
                    )
                    return@queryPurchasesAsync
                }

                continuation.resume(AppResult.Success(purchases))
            }
        }

    private fun PurchaseClaim.complete(result: AppResult<StorePurchaseResult>) {
        purchaseLock.complete(this, result)
    }

    private fun PurchaseClaim.completeFailure(
        code: String,
        message: String,
    ) {
        complete(billingFailure(code, message))
    }

    private fun billingFailure(
        code: String,
        message: String,
    ): AppResult.Failure =
        AppResult.Failure(
            AppError(
                code = code,
                message = message.ifBlank { "Purchase could not be completed." },
            ),
        )

    private fun purchaseInProgress(): AppResult.Failure = billingFailure("BILLING_PURCHASE_IN_PROGRESS", "A purchase is already in progress.")

    private companion object {
        private const val ONE_TIME_PURCHASE = "one_time"
        private const val RESTORE = "restore"
        private val ONE_TIME_PRODUCT_IDS =
            setOf(
                SubscriptionProductId.BOOST_SINGLE,
                SubscriptionProductId.PINEAPPLE_LIKES_FIVE,
                // Retired (Intro Notes became a note on a Pineapple Like). Never sold again, but an
                // unfinished purchase of it is still recovered, verified and credited by the server.
                SubscriptionProductId.LEGACY_INTRO_NOTES_THREE,
            )
    }
}
