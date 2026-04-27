package com.pynapli.server.activity

import com.pynapli.server.auth.AuthException
import com.pynapli.server.auth.AuthService
import com.pynapli.shared.network.dto.RequestAlbumAccessRequestDto
import com.pynapli.shared.network.dto.RespondAlbumAccessRequestDto
import com.pynapli.shared.network.dto.SendFlirtRequestDto
import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import java.util.UUID

fun Application.configureActivityRoutes(
    authService: AuthService?,
    activityService: ActivityService?
) {
    if (authService == null || activityService == null) return

    routing {
        get("/v1/activity") {
            val user = authService.authenticate(call.requiredBearerToken())
            val limit = call.request.queryParameters["limit"]?.toIntOrNull()
            call.respond(activityService.listActivity(user.id, limit ?: 50))
        }

        post("/v1/activity/flirts") {
            val user = authService.authenticate(call.requiredBearerToken())
            val request = call.receive<SendFlirtRequestDto>()
            val targetUserId = request.targetUserId.toUuidOrNull()
                ?: throw ActivityException("INVALID_FLIRT_TARGET")
            call.respond(activityService.sendFlirt(user.id, targetUserId))
        }

        post("/v1/activity/album-requests") {
            val user = authService.authenticate(call.requiredBearerToken())
            val request = call.receive<RequestAlbumAccessRequestDto>()
            val targetUserId = request.targetUserId.toUuidOrNull()
                ?: throw ActivityException("INVALID_ALBUM_REQUEST_TARGET")
            call.respond(activityService.requestAlbumAccess(user.id, targetUserId))
        }

        post("/v1/activity/album-requests/{eventId}/respond") {
            val user = authService.authenticate(call.requiredBearerToken())
            val eventId = call.parameters["eventId"]?.toUuidOrNull()
                ?: throw ActivityException("ALBUM_REQUEST_NOT_FOUND")
            val request = call.receive<RespondAlbumAccessRequestDto>()
            call.respond(activityService.respondToAlbumAccessRequest(user.id, eventId, request.approve))
        }
    }
}

private fun ApplicationCall.requiredBearerToken(): String {
    val value = request.headers[HttpHeaders.Authorization] ?: throw AuthException("UNAUTHENTICATED")
    if (!value.startsWith("Bearer ", ignoreCase = true)) throw AuthException("UNAUTHENTICATED")
    return value.substringAfter("Bearer ").trim().takeIf { it.isNotEmpty() } ?: throw AuthException("UNAUTHENTICATED")
}

private fun String.toUuidOrNull(): UUID? =
    runCatching { UUID.fromString(this) }.getOrNull()
