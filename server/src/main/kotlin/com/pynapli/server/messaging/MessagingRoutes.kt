package com.pynapli.server.messaging

import com.pynapli.server.auth.AuthException
import com.pynapli.server.auth.AuthService
import com.pynapli.shared.network.dto.CreateDirectConversationRequestDto
import com.pynapli.shared.network.dto.SendMessageRequestDto
import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing

fun Application.configureMessagingRoutes(
    authService: AuthService?,
    messagingService: MessagingService?
) {
    if (authService == null || messagingService == null) return

    routing {
        post("/v1/messages/{messageId}/delete") {
            val user = authService.authenticate(call.requiredBearerToken())
            val messageId = call.parameters["messageId"] ?: throw MessagingException("MESSAGE_NOT_FOUND")
            call.respond(messagingService.deleteOwnMessage(user.id, messageId))
        }

        route("/v1/messages/threads") {
            get {
                val user = authService.authenticate(call.requiredBearerToken())
                call.respond(messagingService.listConversations(user.id))
            }

            post {
                val user = authService.authenticate(call.requiredBearerToken())
                val request = call.receive<CreateDirectConversationRequestDto>()
                call.respond(messagingService.createDirectConversation(user.id, request))
            }

            get("/{threadId}") {
                val user = authService.authenticate(call.requiredBearerToken())
                val threadId = call.parameters["threadId"] ?: throw MessagingException("CONVERSATION_NOT_FOUND")
                call.respond(
                    messagingService.getConversationDetail(
                        userId = user.id,
                        conversationIdText = threadId,
                        beforeCursorText = call.request.queryParameters["before"],
                        limitText = call.request.queryParameters["limit"]
                    )
                )
            }

            post("/{threadId}/read") {
                val user = authService.authenticate(call.requiredBearerToken())
                val threadId = call.parameters["threadId"] ?: throw MessagingException("CONVERSATION_NOT_FOUND")
                call.respond(messagingService.markConversationRead(user.id, threadId))
            }

            post("/{threadId}/messages") {
                val user = authService.authenticate(call.requiredBearerToken())
                val threadId = call.parameters["threadId"] ?: throw MessagingException("CONVERSATION_NOT_FOUND")
                val request = call.receive<SendMessageRequestDto>()
                call.respond(messagingService.sendMessage(user.id, threadId, request))
            }

            post("/{threadId}/partner-invite") {
                val user = authService.authenticate(call.requiredBearerToken())
                val threadId = call.parameters["threadId"] ?: throw MessagingException("CONVERSATION_NOT_FOUND")
                call.respond(messagingService.inviteLinkedPartnerToConversation(user.id, threadId))
            }

            post("/{threadId}/partner-invite/accept") {
                val user = authService.authenticate(call.requiredBearerToken())
                val threadId = call.parameters["threadId"] ?: throw MessagingException("PARTNER_INVITE_NOT_FOUND")
                call.respond(messagingService.acceptPartnerInvite(user.id, threadId))
            }

            post("/{threadId}/partner-invite/decline") {
                val user = authService.authenticate(call.requiredBearerToken())
                val threadId = call.parameters["threadId"] ?: throw MessagingException("PARTNER_INVITE_NOT_FOUND")
                call.respond(messagingService.declinePartnerInvite(user.id, threadId))
            }

            post("/{threadId}/leave") {
                val user = authService.authenticate(call.requiredBearerToken())
                val threadId = call.parameters["threadId"] ?: throw MessagingException("CONVERSATION_NOT_FOUND")
                call.respond(messagingService.leaveConversation(user.id, threadId))
            }
        }
    }
}

private fun ApplicationCall.requiredBearerToken(): String {
    val value = request.headers[HttpHeaders.Authorization] ?: throw AuthException("UNAUTHENTICATED")
    if (!value.startsWith("Bearer ", ignoreCase = true)) throw AuthException("UNAUTHENTICATED")
    return value.substringAfter("Bearer ").trim().takeIf { it.isNotEmpty() } ?: throw AuthException("UNAUTHENTICATED")
}
