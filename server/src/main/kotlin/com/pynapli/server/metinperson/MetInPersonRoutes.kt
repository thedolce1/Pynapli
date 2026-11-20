package com.pynapli.server.metinperson

import com.pynapli.server.auth.AuthService
import com.pynapli.server.auth.authenticatedUser
import com.pynapli.server.messaging.MessagingException
import com.pynapli.shared.network.dto.ConfirmMetInPersonRequestDto
import com.pynapli.shared.network.dto.WithdrawMetInPersonRequestDto
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing

/**
 * "We've met in person" from a conversation's overflow menu (TRUST_AND_SAFETY_SPEC "Met in person").
 * Both act only for the token-derived user and answer with that user's own state for the person,
 * which never depends on anyone else's confirmations. Neither notifies anyone.
 */
fun Application.configureMetInPersonRoutes(
    authService: AuthService,
    service: MetInPersonService,
) {
    routing {
        route("/v1/messages/threads/{threadId}/met-in-person") {
            // The caller's own actions for this conversation, asked for when the overflow menu opens.
            get {
                val user = call.authenticatedUser(authService)
                val threadId = call.parameters["threadId"] ?: throw MessagingException(MetInPersonService.NOT_AVAILABLE)
                call.respond(service.offers(user.id, threadId))
            }

            post {
                val user = call.authenticatedUser(authService)
                val threadId = call.parameters["threadId"] ?: throw MessagingException(MetInPersonService.NOT_AVAILABLE)
                val request = call.receive<ConfirmMetInPersonRequestDto>()
                call.respond(service.confirm(user.id, threadId, request))
            }

            post("/withdraw") {
                val user = call.authenticatedUser(authService)
                val threadId = call.parameters["threadId"] ?: throw MessagingException(MetInPersonService.NOT_AVAILABLE)
                val request = call.receive<WithdrawMetInPersonRequestDto>()
                call.respond(service.withdraw(user.id, threadId, request))
            }
        }
    }
}
