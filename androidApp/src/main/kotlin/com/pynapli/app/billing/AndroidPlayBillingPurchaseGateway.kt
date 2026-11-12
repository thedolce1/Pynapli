package com.pynapli.app.billing

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.pynapli.app.store.StorePurchaseGateway
import com.pynapli.app.store.StorePurchaseResult
import com.pynapli.shared.core.result.AppError
import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.PynapliApiClient
import com.pynapli.shared.network.dto.PremiumProductDto
import com.pynapli.shared.network.dto.SubmitPlayStoreSubscriptionPurchaseRequestDto
import com.pynapli.shared.network.dto.SubscriptionPurchaseSyncStatus
import com.pynapli.shared.network.dto.SubscriptionTier
import com.pynapli.shared.network.dto.SubscriptionTierDto
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference
import kotlin.coroutines.resume

class AndroidPlayBillingPurchaseGateway(
    context: Context,
    private val apiClient: PynapliApiClient,
    private val packageNameProvider: () -> String,
) : StorePurchaseGateway,
    PurchasesUpdatedListener {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var activityRef = WeakReference<Activity?>(null)
    private var pendingPurchase: PendingPurchase? = null

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
        if (pendingPurchase != null) {
            return billingFailure("BILLING_PURCHASE_IN_PROGRESS", "A purchase is already in progress.")
        }

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

        val deferred = CompletableDeferred<AppResult<StorePurchaseResult>>()
        pendingPurchase =
            PendingPurchase(
                tier = tier.tier,
                productId = productId,
                deferred = deferred,
            )

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

        val launchResult =
            withContext(Dispatchers.Main.immediate) {
                billingClient.launchBillingFlow(activity, params)
            }
        if (launchResult.responseCode != BillingClient.BillingResponseCode.OK) {
            pendingPurchase = null
            return billingFailure(
                code = "BILLING_LAUNCH_FAILED",
                message = launchResult.debugMessage.ifBlank { "Google Play checkout could not be opened." },
            )
        }

        return deferred.await()
    }

    override suspend fun purchaseProduct(product: PremiumProductDto): AppResult<StorePurchaseResult> = billingFailure("BILLING_PRODUCT_UNAVAILABLE", "This item is not available in Google Play yet.")

    override suspend fun restorePurchases(): AppResult<StorePurchaseResult> {
        if (pendingPurchase != null) {
            return billingFailure("BILLING_PURCHASE_IN_PROGRESS", "A purchase is already in progress.")
        }

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

    override fun onPurchasesUpdated(
        billingResult: BillingResult,
        purchases: MutableList<Purchase>?,
    ) {
        val pending = pendingPurchase ?: return
        when (billingResult.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                val purchase =
                    purchases
                        ?.firstOrNull { purchase -> pending.productId in purchase.products }
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

    private suspend fun queryProductDetails(productId: String): AppResult<ProductDetails> =
        suspendCancellableCoroutine { continuation ->
            val query =
                QueryProductDetailsParams
                    .newBuilder()
                    .setProductList(
                        listOf(
                            QueryProductDetailsParams.Product
                                .newBuilder()
                                .setProductId(productId)
                                .setProductType(BillingClient.ProductType.SUBS)
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

    private suspend fun querySubscriptionPurchases(): AppResult<List<Purchase>> =
        suspendCancellableCoroutine { continuation ->
            val query =
                QueryPurchasesParams
                    .newBuilder()
                    .setProductType(BillingClient.ProductType.SUBS)
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

    private fun PendingPurchase.complete(result: AppResult<StorePurchaseResult>) {
        if (pendingPurchase === this) {
            pendingPurchase = null
        }
        if (!deferred.isCompleted) {
            deferred.complete(result)
        }
    }

    private fun PendingPurchase.completeFailure(
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

    private data class PendingPurchase(
        val tier: String,
        val productId: String,
        val deferred: CompletableDeferred<AppResult<StorePurchaseResult>>,
    )
}
