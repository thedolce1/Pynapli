package com.pynapli.server.subscriptions

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.util.Base64

@Serializable
data class PubSubPushRequestDto(
    val message: PubSubPushMessageDto? = null,
    val subscription: String? = null,
    val deliveryAttempt: Int? = null,
)

@Serializable
data class PubSubPushMessageDto(
    val data: String? = null,
    val attributes: Map<String, String> = emptyMap(),
    val messageId: String? = null,
    @SerialName("message_id")
    val messageIdSnakeCase: String? = null,
    val publishTime: String? = null,
)

@Serializable
data class PlayStoreDeveloperNotificationDto(
    val version: String? = null,
    val packageName: String? = null,
    val eventTimeMillis: JsonElement? = null,
    val subscriptionNotification: PlayStoreSubscriptionNotificationDto? = null,
    val oneTimeProductNotification: PlayStoreOneTimeProductNotificationDto? = null,
    val voidedPurchaseNotification: PlayStoreVoidedPurchaseNotificationDto? = null,
    val testNotification: PlayStoreTestNotificationDto? = null,
)

@Serializable
data class PlayStoreSubscriptionNotificationDto(
    val version: String? = null,
    val notificationType: Int? = null,
    val purchaseToken: String? = null,
    val subscriptionId: String? = null,
)

@Serializable
data class PlayStoreOneTimeProductNotificationDto(
    val version: String? = null,
    val notificationType: Int? = null,
    val purchaseToken: String? = null,
    val sku: String? = null,
)

@Serializable
data class PlayStoreVoidedPurchaseNotificationDto(
    val purchaseToken: String? = null,
    val orderId: String? = null,
    val productType: Int? = null,
    val refundType: Int? = null,
)

@Serializable
class PlayStoreTestNotificationDto

data class PlayStoreRtdnEnvelope(
    val messageId: String,
    val rawPayload: String,
    val notification: PlayStoreDeveloperNotificationDto,
) {
    val eventType: String
        get() =
            when {
                notification.subscriptionNotification != null -> "subscription"
                notification.voidedPurchaseNotification != null -> "voided_purchase"
                notification.oneTimeProductNotification != null -> "one_time_product"
                notification.testNotification != null -> "test"
                else -> "unknown"
            }

    val notificationType: Int?
        get() =
            notification.subscriptionNotification?.notificationType
                ?: notification.oneTimeProductNotification?.notificationType

    val purchaseToken: String?
        get() =
            notification.subscriptionNotification?.purchaseToken
                ?: notification.voidedPurchaseNotification?.purchaseToken
                ?: notification.oneTimeProductNotification?.purchaseToken

    val productId: String?
        get() =
            notification.subscriptionNotification?.subscriptionId
                ?: notification.oneTimeProductNotification?.sku

    val eventTime: Instant?
        get() =
            notification.eventTimeMillis
                ?.jsonPrimitive
                ?.contentOrNull
                ?.toLongOrNull()
                ?.let(Instant::ofEpochMilli)
}

data class PlayStoreRtdnProcessingResult(
    val status: String,
    val eventType: String,
    val messageId: String,
    val purchaseToken: String? = null,
    val productId: String? = null,
    val subscriptionStatus: String? = null,
    val errorCode: String? = null,
)

fun PubSubPushRequestDto.toPlayStoreRtdnEnvelope(json: Json): PlayStoreRtdnEnvelope {
    val pubSubMessage = message ?: throw SubscriptionException("INVALID_RTDN_MESSAGE")
    val messageId =
        pubSubMessage.messageId
            ?: pubSubMessage.messageIdSnakeCase
            ?: throw SubscriptionException("INVALID_RTDN_MESSAGE")
    val data =
        pubSubMessage.data?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw SubscriptionException("INVALID_RTDN_MESSAGE")
    val rawPayload =
        runCatching {
            String(Base64.getDecoder().decode(data), Charsets.UTF_8)
        }.getOrElse {
            throw SubscriptionException("INVALID_RTDN_MESSAGE")
        }
    val notification =
        runCatching {
            json.decodeFromString<PlayStoreDeveloperNotificationDto>(rawPayload)
        }.getOrElse {
            throw SubscriptionException("INVALID_RTDN_MESSAGE")
        }
    return PlayStoreRtdnEnvelope(
        messageId = messageId,
        rawPayload = rawPayload,
        notification = notification,
    )
}
