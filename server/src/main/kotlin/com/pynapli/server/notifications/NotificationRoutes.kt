package com.pynapli.server.notifications

import com.pynapli.server.auth.AuthException
import com.pynapli.server.auth.AuthService
import com.pynapli.shared.network.dto.RegisterPushDeviceRequestDto
import com.pynapli.shared.network.dto.UnregisterPushDeviceRequestDto
import com.pynapli.shared.network.dto.UpdateNotificationPreferencesRequestDto
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing

fun Application.configureNotificationRoutes(
    authService: AuthService?,
    notificationService: NotificationService?,
) {
    if (authService == null || notificationService == null) return

    routing {
        route("/v1/notifications") {
            get("/preferences") {
                val user = authService.authenticate(call.requiredBearerToken())
                call.respond(notificationService.getPreferences(user.id))
            }

            patch("/preferences") {
                val user = authService.authenticate(call.requiredBearerToken())
                val request = call.receive<UpdateNotificationPreferencesRequestDto>()
                call.respond(notificationService.updatePreferences(user.id, request))
            }

            post("/devices") {
                val user = authService.authenticate(call.requiredBearerToken())
                val request = call.receive<RegisterPushDeviceRequestDto>()
                call.respond(notificationService.registerPushDevice(user.id, request))
            }

            post("/devices/unregister") {
                val user = authService.authenticate(call.requiredBearerToken())
                val request = call.receive<UnregisterPushDeviceRequestDto>()
                notificationService.unregisterPushDevice(user.id, request)
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}

private fun ApplicationCall.requiredBearerToken(): String {
    val value = request.headers[HttpHeaders.Authorization] ?: throw AuthException("UNAUTHENTICATED")
    if (!value.startsWith("Bearer ", ignoreCase = true)) throw AuthException("UNAUTHENTICATED")
    return value.substringAfter("Bearer ").trim().takeIf { it.isNotEmpty() } ?: throw AuthException("UNAUTHENTICATED")
}
