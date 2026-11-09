package com.pynapli.server.messaging

import com.pynapli.server.auth.AuthService
import com.pynapli.server.auth.authenticatedUser
import com.pynapli.shared.network.dto.CreateDirectConversationRequestDto
import com.pynapli.shared.network.dto.CreateMessageMediaUploadRequestDto
import com.pynapli.shared.network.dto.SendMediaMessageRequestDto
import com.pynapli.shared.network.dto.SendMessageRequestDto
import com.pynapli.shared.network.dto.SendTypingIndicatorRequestDto
import com.pynapli.shared.network.dto.SetConversationExpiryRequestDto
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing

fun Application.configureMessagingRoutes(
    authService: AuthService,
    messagingService: MessagingService,
) {
    routing {
        post("/v1/messages/{messageId}/delete") {
            val user = call.authenticatedUser(authService)
            val messageId = call.parameters["messageId"] ?: throw MessagingException("MESSAGE_NOT_FOUND")
            call.respond(messagingService.deleteOwnMessage(user.id, messageId))
        }

        post("/v1/messages/{messageId}/reveal") {
            val user = call.authenticatedUser(authService)
            val messageId = call.parameters["messageId"] ?: throw MessagingException("MESSAGE_NOT_FOUND")
            call.respond(messagingService.revealMessageMedia(user.id, messageId))
        }

        post("/v1/messages/media/uploads") {
            val user = call.authenticatedUser(authService)
            val request = call.receive<CreateMessageMediaUploadRequestDto>()
            call.respond(messagingService.createMessageMediaUploadSlot(user.id, request))
        }

        route("/v1/messages/threads") {
            get {
                val user = call.authenticatedUser(authService)
                call.respond(messagingService.listConversations(user.id))
            }

            post {
                val user = call.authenticatedUser(authService)
                val request = call.receive<CreateDirectConversationRequestDto>()
                call.respond(messagingService.createDirectConversation(user.id, request))
            }

            get("/{threadId}") {
                val user = call.authenticatedUser(authService)
                val threadId = call.parameters["threadId"] ?: throw MessagingException("CONVERSATION_NOT_FOUND")
                call.respond(
                    messagingService.getConversationDetail(
                        userId = user.id,
                        conversationIdText = threadId,
                        beforeCursorText = call.request.queryParameters["before"],
                        limitText = call.request.queryParameters["limit"],
                    ),
                )
            }

            post("/{threadId}/read") {
                val user = call.authenticatedUser(authService)
                val threadId = call.parameters["threadId"] ?: throw MessagingException("CONVERSATION_NOT_FOUND")
                call.respond(messagingService.markConversationRead(user.id, threadId))
            }

            post("/{threadId}/messages") {
                val user = call.authenticatedUser(authService)
                val threadId = call.parameters["threadId"] ?: throw MessagingException("CONVERSATION_NOT_FOUND")
                val request = call.receive<SendMessageRequestDto>()
                call.respond(messagingService.sendMessage(user.id, threadId, request))
            }

            put("/{threadId}/expiry") {
                val user = call.authenticatedUser(authService)
                val threadId = call.parameters["threadId"] ?: throw MessagingException("CONVERSATION_NOT_FOUND")
                val request = call.receive<SetConversationExpiryRequestDto>()
                call.respond(messagingService.setConversationExpiry(user.id, threadId, request.expirySeconds))
            }

            post("/{threadId}/media") {
                val user = call.authenticatedUser(authService)
                val threadId = call.parameters["threadId"] ?: throw MessagingException("CONVERSATION_NOT_FOUND")
                val request = call.receive<SendMediaMessageRequestDto>()
                call.respond(messagingService.sendMediaMessage(user.id, threadId, request))
            }

            post("/{threadId}/typing") {
                val user = call.authenticatedUser(authService)
                val threadId = call.parameters["threadId"] ?: throw MessagingException("CONVERSATION_NOT_FOUND")
                val request = call.receive<SendTypingIndicatorRequestDto>()
                messagingService.sendTypingIndicator(user.id, threadId, request)
                call.respond(mapOf("ok" to true))
            }

            post("/{threadId}/partner-invite") {
                val user = call.authenticatedUser(authService)
                val threadId = call.parameters["threadId"] ?: throw MessagingException("CONVERSATION_NOT_FOUND")
                call.respond(messagingService.inviteLinkedPartnerToConversation(user.id, threadId))
            }

            post("/{threadId}/partner-invite/accept") {
                val user = call.authenticatedUser(authService)
                val threadId = call.parameters["threadId"] ?: throw MessagingException("PARTNER_INVITE_NOT_FOUND")
                call.respond(messagingService.acceptPartnerInvite(user.id, threadId))
            }

            post("/{threadId}/partner-invite/decline") {
                val user = call.authenticatedUser(authService)
                val threadId = call.parameters["threadId"] ?: throw MessagingException("PARTNER_INVITE_NOT_FOUND")
                call.respond(messagingService.declinePartnerInvite(user.id, threadId))
            }

            post("/{threadId}/leave") {
                val user = call.authenticatedUser(authService)
                val threadId = call.parameters["threadId"] ?: throw MessagingException("CONVERSATION_NOT_FOUND")
                call.respond(messagingService.leaveConversation(user.id, threadId))
            }
        }
    }
}
