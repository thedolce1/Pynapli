package com.pynapli.server.presence

import com.pynapli.server.auth.AuthService
import com.pynapli.server.auth.authenticatedUser
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing

/**
 * `POST /v1/presence/heartbeat` (plan G2): a foregrounded app that is sitting idle (no other request,
 * no open socket) calls this about every 60 s so its green dot holds. It carries no body and returns
 * 204 with no body: nothing about anyone's presence is echoed. Every authenticated request already
 * marks the caller online (AuthService hook), so this route only exists for the idle case; the
 * per-user claim in [PresenceService] keeps it to one write a minute however often it is called.
 */
fun Application.configurePresenceHeartbeatRoutes(
    authService: AuthService,
    presenceService: PresenceService,
) {
    routing {
        post("/v1/presence/heartbeat") {
            val user = call.authenticatedUser(authService)
            presenceService.touch(user.id)
            call.respond(HttpStatusCode.NoContent)
        }
    }
}
