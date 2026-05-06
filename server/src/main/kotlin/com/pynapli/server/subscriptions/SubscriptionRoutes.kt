package com.pynapli.server.subscriptions

import com.pynapli.server.auth.AuthException
import com.pynapli.server.auth.AuthService
import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

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
    }
}

private fun ApplicationCall.requiredBearerToken(): String {
    val value = request.headers[HttpHeaders.Authorization] ?: throw AuthException("UNAUTHENTICATED")
    if (!value.startsWith("Bearer ", ignoreCase = true)) throw AuthException("UNAUTHENTICATED")
    return value.substringAfter("Bearer ").trim().takeIf { it.isNotEmpty() } ?: throw AuthException("UNAUTHENTICATED")
}
