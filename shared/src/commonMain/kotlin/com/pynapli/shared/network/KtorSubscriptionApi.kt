package com.pynapli.shared.network

import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.dto.BoostActivationDto
import com.pynapli.shared.network.dto.StorePurchaseResponseDto
import com.pynapli.shared.network.dto.SubmitDevStorePurchaseRequestDto
import com.pynapli.shared.network.dto.SubmitPlayStoreSubscriptionPurchaseRequestDto
import com.pynapli.shared.network.dto.SubscriptionEntitlementDto
import com.pynapli.shared.network.dto.SubscriptionInventoryResponseDto
import com.pynapli.shared.network.dto.SubscriptionPurchaseSyncResponseDto
import io.ktor.client.request.get
import io.ktor.http.HttpMethod

/** Ktor implementation of [SubscriptionApi], composed into `KtorPynapliApiClient` via delegation. */
internal class KtorSubscriptionApi(
    private val support: KtorApiSupport,
) : SubscriptionApi {
    override suspend fun getSubscriptionEntitlement(): AppResult<SubscriptionEntitlementDto> = support.authGet("/v1/subscriptions/entitlement")

    override suspend fun getSubscriptionInventory(): AppResult<SubscriptionInventoryResponseDto> = support.authGet("/v1/subscriptions/inventory")

    override suspend fun submitPlayStoreSubscriptionPurchase(
        request: SubmitPlayStoreSubscriptionPurchaseRequestDto,
    ): AppResult<SubscriptionPurchaseSyncResponseDto> = support.authSend(HttpMethod.Post, "/v1/subscriptions/play-store/purchases", request)

    override suspend fun submitDevStorePurchase(request: SubmitDevStorePurchaseRequestDto): AppResult<StorePurchaseResponseDto> = support.authSend(HttpMethod.Post, "/v1/subscriptions/dev/purchases", request)

    override suspend fun activateBoost(): AppResult<BoostActivationDto> = support.authPost("/v1/boosts")
}
