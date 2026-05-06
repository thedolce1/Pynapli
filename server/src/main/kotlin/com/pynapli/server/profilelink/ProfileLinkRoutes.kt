package com.pynapli.server.profilelink

import com.pynapli.server.auth.AuthException
import com.pynapli.server.auth.AuthService
import com.pynapli.shared.network.dto.CreateProfileLinkInvitationRequestDto
import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing

fun Application.configureProfileLinkRoutes(
    authService: AuthService?,
    profileLinkService: ProfileLinkService?
) {
    if (authService == null || profileLinkService == null) return

    routing {
        route("/v1/profile-links") {
            get {
                val user = authService.authenticate(call.requiredBearerToken())
                call.respond(profileLinkService.getSummary(user.id))
            }

            post("/invitations") {
                val user = authService.authenticate(call.requiredBearerToken())
                val request = call.receive<CreateProfileLinkInvitationRequestDto>()
                call.respond(profileLinkService.createInvitation(user.id, request))
            }

            post("/invitations/{linkId}/accept") {
                val user = authService.authenticate(call.requiredBearerToken())
                val linkId = call.parameters["linkId"] ?: throw ProfileLinkException("PROFILE_LINK_INVITE_NOT_FOUND")
                call.respond(profileLinkService.acceptInvitation(user.id, linkId))
            }

            post("/invitations/{linkId}/decline") {
                val user = authService.authenticate(call.requiredBearerToken())
                val linkId = call.parameters["linkId"] ?: throw ProfileLinkException("PROFILE_LINK_INVITE_NOT_FOUND")
                call.respond(profileLinkService.declineInvitation(user.id, linkId))
            }

            delete("/current") {
                val user = authService.authenticate(call.requiredBearerToken())
                call.respond(profileLinkService.unlinkCurrent(user.id))
            }
        }
    }
}

private fun ApplicationCall.requiredBearerToken(): String {
    val value = request.headers[HttpHeaders.Authorization] ?: throw AuthException("UNAUTHENTICATED")
    if (!value.startsWith("Bearer ", ignoreCase = true)) throw AuthException("UNAUTHENTICATED")
    return value.substringAfter("Bearer ").trim().takeIf { it.isNotEmpty() } ?: throw AuthException("UNAUTHENTICATED")
}
