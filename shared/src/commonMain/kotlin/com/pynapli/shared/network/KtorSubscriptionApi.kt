package com.pynapli.shared.network

import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.dto.BoostActivationDto
import com.pynapli.shared.network.dto.StorePurchaseResponseDto
import com.pynapli.shared.network.dto.SubmitDevStorePurchaseRequestDto
import com.pynapli.shared.network.dto.SubmitPlayStoreSubscriptionPurchaseRequestDto
import com.pynapli.shared.network.dto.SubscriptionEntitlementDto
import com.pynapli.shared.network.dto.SubscriptionInventoryResponseDto
import com.pynapli.shared.network.dto.SubscriptionPurchaseSyncResponseDto
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType

/** Ktor implementation of [SubscriptionApi], composed into `KtorPynapliApiClient` via delegation. */
internal class KtorSubscriptionApi(
    private val support: KtorApiSupport,
) : SubscriptionApi {
    override suspend fun getSubscriptionEntitlement(): AppResult<SubscriptionEntitlementDto> =
        support.authenticated { token ->
            support.httpClient
                .get(support.endpoint("/v1/subscriptions/entitlement")) {
                    bearerAuth(token)
                }.toAppResult(support.json)
        }

    override suspend fun getSubscriptionInventory(): AppResult<SubscriptionInventoryResponseDto> =
        support.authenticated { token ->
            support.httpClient
                .get(support.endpoint("/v1/subscriptions/inventory")) {
                    bearerAuth(token)
                }.toAppResult(support.json)
        }

    override suspend fun submitPlayStoreSubscriptionPurchase(
        request: SubmitPlayStoreSubscriptionPurchaseRequestDto,
    ): AppResult<SubscriptionPurchaseSyncResponseDto> =
        support.authenticated { token ->
            support.httpClient
                .post(support.endpoint("/v1/subscriptions/play-store/purchases")) {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }.toAppResult(support.json)
        }

    override suspend fun submitDevStorePurchase(request: SubmitDevStorePurchaseRequestDto): AppResult<StorePurchaseResponseDto> =
        support.authenticated { token ->
            support.httpClient
                .post(support.endpoint("/v1/subscriptions/dev/purchases")) {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }.toAppResult(support.json)
        }

    override suspend fun activateBoost(): AppResult<BoostActivationDto> =
        support.authenticated { token ->
            support.httpClient
                .post(support.endpoint("/v1/boosts")) {
                    bearerAuth(token)
                }.toAppResult(support.json)
        }
}
