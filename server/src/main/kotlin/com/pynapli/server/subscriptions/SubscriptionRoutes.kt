package com.pynapli.server.subscriptions

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
import kotlinx.serialization.Serializable
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

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
