package com.pynapli.server.subscriptions

import com.google.auth.oauth2.TokenVerifier
import com.pynapli.server.auth.AuthException
import com.pynapli.server.auth.AuthService
import com.pynapli.server.config.ServerConfig
import com.pynapli.shared.network.dto.SubmitPlayStoreSubscriptionPurchaseRequestDto
import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import kotlinx.serialization.Serializable
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

private val rtdnJson = Json { ignoreUnknownKeys = true }

fun Application.configureSubscriptionRoutes(
    authService: AuthService?,
    subscriptionService: SubscriptionService?
) {
    if (authService == null || subscriptionService == null) return

    routing {
        get("/v1/subscriptions/entitlement") {
            val user = authService.authenticate(call.requiredBearerToken())
            call.respond(subscriptionService.getEntitlement(user.id))
        }

        get("/v1/subscriptions/inventory") {
            authService.authenticate(call.requiredBearerToken())
            call.respond(subscriptionService.getInventory())
        }

        post("/v1/subscriptions/play-store/purchases") {
            val user = authService.authenticate(call.requiredBearerToken())
            val request = call.receive<SubmitPlayStoreSubscriptionPurchaseRequestDto>()
            call.respond(subscriptionService.recordPlayStoreSubscriptionPurchase(user.id, request))
        }
    }
}

fun Application.configureSubscriptionAdminRoutes(
    config: ServerConfig,
    subscriptionService: SubscriptionService?
) {
    val adminApiToken = config.adminApiToken ?: return
    if (subscriptionService == null) return

    routing {
        post("/v1/admin/subscriptions/play-store/sync") {
            call.requireSubscriptionAdminActor(adminApiToken)
            val limit = call.request.queryParameters["limit"]
                ?.toIntOrNull()
                ?.coerceIn(1, 500)
                ?: config.playStoreSubscriptionSyncBatchSize
            call.respond(subscriptionService.syncDuePlayStoreSubscriptions(limit).toDto())
        }
    }
}

fun Application.configurePlayStoreRtdnRoutes(
    config: ServerConfig,
    subscriptionService: SubscriptionService?
) {
    if (!config.playStoreRtdnEnabled || subscriptionService == null) return
    if (config.playStoreRtdnWebhookToken == null && config.playStoreRtdnOidcAudience == null) {
        throw IllegalStateException(
            "Play Store RTDN requires PYNAPLI_PLAY_STORE_RTDN_WEBHOOK_TOKEN or PYNAPLI_PLAY_STORE_RTDN_OIDC_AUDIENCE."
        )
    }

    routing {
        post("/v1/webhooks/play-store/rtdn") {
            call.requirePlayStoreRtdnAuth(config)
            val request = call.receive<PubSubPushRequestDto>()
            call.respond(
                subscriptionService
                    .handlePlayStoreRealTimeDeveloperNotification(request.toPlayStoreRtdnEnvelope(rtdnJson))
                    .toDto()
            )
        }
    }
}

private fun ApplicationCall.requiredBearerToken(): String {
    val value = request.headers[HttpHeaders.Authorization] ?: throw AuthException("UNAUTHENTICATED")
    if (!value.startsWith("Bearer ", ignoreCase = true)) throw AuthException("UNAUTHENTICATED")
    return value.substringAfter("Bearer ").trim().takeIf { it.isNotEmpty() } ?: throw AuthException("UNAUTHENTICATED")
}

private fun ApplicationCall.requireSubscriptionAdminActor(expectedToken: String): String {
    val suppliedToken = request.headers["X-Admin-Token"]
        ?: request.headers[HttpHeaders.Authorization]
            ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
            ?.substringAfter("Bearer ")
            ?.trim()
        ?: throw SubscriptionException("ADMIN_UNAUTHENTICATED")

    if (!MessageDigest.isEqual(
            expectedToken.toByteArray(StandardCharsets.UTF_8),
            suppliedToken.toByteArray(StandardCharsets.UTF_8)
        )
    ) {
        throw SubscriptionException("ADMIN_FORBIDDEN")
    }

    return request.headers["X-Admin-Id"]?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: throw SubscriptionException("INVALID_ADMIN_ACTOR")
}

private fun ApplicationCall.requirePlayStoreRtdnAuth(config: ServerConfig) {
    val configuredToken = config.playStoreRtdnWebhookToken
    val suppliedStaticToken = request.headers["X-Pynapli-Webhook-Token"]
        ?: request.queryParameters["token"]
    if (
        configuredToken != null &&
        suppliedStaticToken != null &&
        MessageDigest.isEqual(
            configuredToken.toByteArray(StandardCharsets.UTF_8),
            suppliedStaticToken.toByteArray(StandardCharsets.UTF_8)
        )
    ) {
        return
    }

    val audience = config.playStoreRtdnOidcAudience ?: throw SubscriptionException("RTDN_UNAUTHENTICATED")
    val bearerToken = request.headers[HttpHeaders.Authorization]
        ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
        ?.substringAfter("Bearer ")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: throw SubscriptionException("RTDN_UNAUTHENTICATED")

    val payload = runCatching {
        TokenVerifier.newBuilder()
            .setIssuer("https://accounts.google.com")
            .setAudience(audience)
            .build()
            .verify(bearerToken)
            .payload
    }.getOrElse {
        throw SubscriptionException("RTDN_FORBIDDEN")
    }

    val expectedEmail = config.playStoreRtdnOidcServiceAccountEmail ?: return
    val actualEmail = payload["email"]?.toString()
    val emailVerified = payload["email_verified"] as? Boolean ?: false
    if (actualEmail != expectedEmail || !emailVerified) {
        throw SubscriptionException("RTDN_FORBIDDEN")
    }
}

@Serializable
data class PlayStoreSubscriptionSyncSummaryDto(
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

@Serializable
data class PlayStoreRtdnProcessingResultDto(
    val status: String,
    val eventType: String,
    val messageId: String,
    val purchaseToken: String? = null,
    val productId: String? = null,
    val subscriptionStatus: String? = null,
    val errorCode: String? = null
)

private fun PlayStoreSubscriptionSyncSummary.toDto(): PlayStoreSubscriptionSyncSummaryDto =
    PlayStoreSubscriptionSyncSummaryDto(
        scanned = scanned,
        verified = verified,
        active = active,
        grace = grace,
        pending = pending,
        expired = expired,
        revoked = revoked,
        invalid = invalid,
        unavailable = unavailable
    )

private fun PlayStoreRtdnProcessingResult.toDto(): PlayStoreRtdnProcessingResultDto =
    PlayStoreRtdnProcessingResultDto(
        status = status,
        eventType = eventType,
        messageId = messageId,
        purchaseToken = purchaseToken,
        productId = productId,
        subscriptionStatus = subscriptionStatus,
        errorCode = errorCode
    )
