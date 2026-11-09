package com.pynapli.server.activity

import com.pynapli.server.auth.AuthService
import com.pynapli.server.auth.authenticatedUser
import com.pynapli.shared.network.dto.MarkActivitySeenRequestDto
import com.pynapli.shared.network.dto.RequestAlbumAccessRequestDto
import com.pynapli.shared.network.dto.RespondAlbumAccessRequestDto
import com.pynapli.shared.network.dto.SendFlirtRequestDto
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import java.util.UUID

fun Application.configureActivityRoutes(
    authService: AuthService,
    activityService: ActivityService,
) {
    routing {
        get("/v1/activity") {
            val user = call.authenticatedUser(authService)
            val limit = call.request.queryParameters["limit"]?.toIntOrNull()
            val tab = call.request.queryParameters["tab"]
            val cursor = call.request.queryParameters["cursor"]
            call.respond(
                activityService.listActivity(
                    recipientUserId = user.id,
                    limit = limit ?: 50,
                    tabText = tab,
                    cursorText = cursor,
                ),
            )
        }

        get("/v1/activity/summary") {
            val user = call.authenticatedUser(authService)
            call.respond(activityService.getActivitySummary(user.id))
        }

        post("/v1/activity/seen") {
            val user = call.authenticatedUser(authService)
            val request = call.receive<MarkActivitySeenRequestDto>()
            call.respond(activityService.markActivityEventsSeen(user.id, request.eventIds))
        }

        post("/v1/activity/{eventId}/dismiss") {
            val user = call.authenticatedUser(authService)
            val eventId =
                call.parameters["eventId"]?.toUuidOrNull()
                    ?: throw ActivityException("ACTIVITY_EVENT_NOT_FOUND")
            call.respond(activityService.dismissActivityEvent(user.id, eventId))
        }

        post("/v1/activity/flirts") {
            val user = call.authenticatedUser(authService)
            val request = call.receive<SendFlirtRequestDto>()
            val targetUserId =
                request.targetUserId.toUuidOrNull()
                    ?: throw ActivityException("INVALID_FLIRT_TARGET")
            call.respond(activityService.sendFlirt(user.id, targetUserId, request.source))
        }

        post("/v1/activity/album-requests") {
            val user = call.authenticatedUser(authService)
            val request = call.receive<RequestAlbumAccessRequestDto>()
            val targetUserId =
                request.targetUserId.toUuidOrNull()
                    ?: throw ActivityException("INVALID_ALBUM_REQUEST_TARGET")
            call.respond(activityService.requestAlbumAccess(user.id, targetUserId))
        }

        post("/v1/activity/album-requests/{eventId}/respond") {
            val user = call.authenticatedUser(authService)
            val eventId =
                call.parameters["eventId"]?.toUuidOrNull()
                    ?: throw ActivityException("ALBUM_REQUEST_NOT_FOUND")
            val request = call.receive<RespondAlbumAccessRequestDto>()
            call.respond(activityService.respondToAlbumAccessRequest(user.id, eventId, request.approve))
        }
    }
}

private fun String.toUuidOrNull(): UUID? = runCatching { UUID.fromString(this) }.getOrNull()
