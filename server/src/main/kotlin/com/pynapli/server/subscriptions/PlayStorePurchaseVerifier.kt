package com.pynapli.server.subscriptions

import com.google.auth.oauth2.GoogleCredentials
import com.pynapli.server.config.ServerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.io.FileInputStream
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant

interface PlayStorePurchaseVerifier {
    suspend fun verifySubscriptionPurchase(
        packageName: String,
        productId: String,
        purchaseToken: String
    ): PlayStorePurchaseVerificationResult
}

data object DisabledPlayStorePurchaseVerifier : PlayStorePurchaseVerifier {
    override suspend fun verifySubscriptionPurchase(
        packageName: String,
        productId: String,
        purchaseToken: String
    ): PlayStorePurchaseVerificationResult = PlayStorePurchaseVerificationResult.VerificationUnavailable
}

sealed interface PlayStorePurchaseVerificationResult {
    data class Verified(val purchase: VerifiedPlayStoreSubscriptionPurchase) : PlayStorePurchaseVerificationResult
    data class Invalid(val code: String) : PlayStorePurchaseVerificationResult
    data object VerificationUnavailable : PlayStorePurchaseVerificationResult
}

data class VerifiedPlayStoreSubscriptionPurchase(
    val productId: String,
    val purchaseToken: String,
    val latestOrderId: String?,
    val subscriptionState: String,
    val acknowledgementState: String?,
    val currentPeriodStart: Instant,
    val currentPeriodEnd: Instant
)

class GooglePlayStorePurchaseVerifier private constructor(
    private val developerApiBaseUrl: String,
    private val credentials: GoogleCredentials,
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()
) : PlayStorePurchaseVerifier {
    private val logger = LoggerFactory.getLogger(GooglePlayStorePurchaseVerifier::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun verifySubscriptionPurchase(
        packageName: String,
        productId: String,
        purchaseToken: String
    ): PlayStorePurchaseVerificationResult = withContext(Dispatchers.IO) {
        runCatching {
            val response = sendAuthorized(
                HttpRequest.newBuilder()
                    .uri(subscriptionV2Uri(packageName, purchaseToken))
                    .GET()
            )

            when (response.statusCode()) {
                in 200..299 -> verifySuccessfulResponse(packageName, productId, purchaseToken, response.body())
                400, 404 -> PlayStorePurchaseVerificationResult.Invalid("INVALID_PURCHASE_TOKEN")
                401, 403 -> {
                    logger.error("Google Play verification is unauthorized: status={}", response.statusCode())
                    PlayStorePurchaseVerificationResult.VerificationUnavailable
                }
                else -> {
                    logger.warn(
                        "Google Play verification was unavailable: status={} body={}",
                        response.statusCode(),
                        response.body().take(240)
                    )
                    PlayStorePurchaseVerificationResult.VerificationUnavailable
                }
            }
        }.getOrElse { error ->
            logger.warn("Google Play verification failed.", error)
            PlayStorePurchaseVerificationResult.VerificationUnavailable
        }
    }

    private fun verifySuccessfulResponse(
        packageName: String,
        productId: String,
        purchaseToken: String,
        responseBody: String
    ): PlayStorePurchaseVerificationResult {
        val root = json.parseToJsonElement(responseBody).jsonObject
        val subscriptionState = root.stringOrNull("subscriptionState")
            ?: return PlayStorePurchaseVerificationResult.Invalid("INVALID_PURCHASE_TOKEN")
        val startTime = root.stringOrNull("startTime")?.let(Instant::parse)
            ?: return PlayStorePurchaseVerificationResult.Invalid("INVALID_PURCHASE_TOKEN")
        val lineItem = root["lineItems"]
            ?.jsonArray
            ?.mapNotNull { element -> element as? JsonObject }
            ?.firstOrNull { it.stringOrNull("productId") == productId }
            ?: return PlayStorePurchaseVerificationResult.Invalid("INVALID_SUBSCRIPTION_PRODUCT")
        val expiryTime = lineItem.stringOrNull("expiryTime")?.let(Instant::parse)
            ?: return PlayStorePurchaseVerificationResult.Invalid("INVALID_PURCHASE_TOKEN")

        val purchase = VerifiedPlayStoreSubscriptionPurchase(
            productId = productId,
            purchaseToken = purchaseToken,
            latestOrderId = root.stringOrNull("latestOrderId"),
            subscriptionState = subscriptionState,
            acknowledgementState = root.stringOrNull("acknowledgementState"),
            currentPeriodStart = startTime,
            currentPeriodEnd = expiryTime
        )

        if (
            purchase.acknowledgementState == ACKNOWLEDGEMENT_STATE_PENDING &&
            purchase.subscriptionState in ACKNOWLEDGEMENT_ELIGIBLE_STATES
        ) {
            val acknowledgeResponse = sendAuthorized(
                HttpRequest.newBuilder()
                    .uri(acknowledgeUri(packageName, productId, purchaseToken))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(ACKNOWLEDGE_BODY))
            )
            if (acknowledgeResponse.statusCode() !in 200..299) {
                logger.warn(
                    "Google Play acknowledgement failed: status={} body={}",
                    acknowledgeResponse.statusCode(),
                    acknowledgeResponse.body().take(240)
                )
                return PlayStorePurchaseVerificationResult.VerificationUnavailable
            }
            return PlayStorePurchaseVerificationResult.Verified(
                purchase.copy(acknowledgementState = ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED)
            )
        }

        return PlayStorePurchaseVerificationResult.Verified(purchase)
    }

    private fun sendAuthorized(builder: HttpRequest.Builder): HttpResponse<String> {
        credentials.refreshIfExpired()
        val token = credentials.accessToken?.tokenValue
            ?: throw IllegalStateException("Google credentials did not return an access token.")
        return httpClient.send(
            builder
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(20))
                .build(),
            HttpResponse.BodyHandlers.ofString()
        )
    }

    private fun subscriptionV2Uri(packageName: String, purchaseToken: String): URI =
        URI.create(
            "$developerApiBaseUrl/androidpublisher/v3/applications/${packageName.pathSegment()}" +
                "/purchases/subscriptionsv2/tokens/${purchaseToken.pathSegment()}"
        )

    private fun acknowledgeUri(packageName: String, productId: String, purchaseToken: String): URI =
        URI.create(
            "$developerApiBaseUrl/androidpublisher/v3/applications/${packageName.pathSegment()}" +
                "/purchases/subscriptions/${productId.pathSegment()}/tokens/${purchaseToken.pathSegment()}:acknowledge"
        )

    private fun JsonObject.stringOrNull(key: String): String? =
        get(key)?.jsonPrimitive?.contentOrNull

    companion object {
        private const val ANDROID_PUBLISHER_SCOPE = "https://www.googleapis.com/auth/androidpublisher"
        private const val ACKNOWLEDGEMENT_STATE_PENDING = "ACKNOWLEDGEMENT_STATE_PENDING"
        private const val ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED = "ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED"
        private const val ACKNOWLEDGE_BODY = """{"developerPayload":"pynapli-server"}"""
        private val ACKNOWLEDGEMENT_ELIGIBLE_STATES = setOf(
            "SUBSCRIPTION_STATE_ACTIVE",
            "SUBSCRIPTION_STATE_CANCELED",
            "SUBSCRIPTION_STATE_IN_GRACE_PERIOD"
        )

        fun fromConfig(config: ServerConfig): GooglePlayStorePurchaseVerifier {
            val credentials = config.playStoreServiceAccountJson?.let { json ->
                ByteArrayInputStream(json.toByteArray(StandardCharsets.UTF_8)).use(GoogleCredentials::fromStream)
            } ?: config.playStoreServiceAccountPath?.let { path ->
                FileInputStream(path).use(GoogleCredentials::fromStream)
            } ?: GoogleCredentials.getApplicationDefault()

            return GooglePlayStorePurchaseVerifier(
                developerApiBaseUrl = config.playStoreDeveloperApiBaseUrl.trimEnd('/'),
                credentials = credentials.createScoped(listOf(ANDROID_PUBLISHER_SCOPE))
            )
        }
    }
}

private fun String.pathSegment(): String =
    URLEncoder.encode(this, StandardCharsets.UTF_8).replace("+", "%20")
