package com.pynapli.server.metinperson

import com.pynapli.server.auth.AuthService
import com.pynapli.server.auth.authenticatedUser
import com.pynapli.server.messaging.MessagingException
import com.pynapli.shared.network.dto.ConfirmMetInPersonRequestDto
import com.pynapli.shared.network.dto.MetInPersonConfirmationRequestDto
import com.pynapli.shared.network.dto.MetInPersonOffersRequestDto
import com.pynapli.shared.network.dto.WithdrawMetInPersonRequestDto
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlin.coroutines.cancellation.CancellationException

/**
 * "We've met in person" from a conversation's overflow menu (TRUST_AND_SAFETY_SPEC "Met in person").
 * Both act only for the token-derived user and answer with that user's own state for the person,
 * which never depends on anyone else's confirmations. Neither notifies anyone.
 *
 * Nothing identifying is in the path: the conversation, the person and the action travel in the body,
 * because request logs (ours and Cloud Run's) record method and path, and a path per conversation and
 * action would pin who confirmed whom. A malformed body or an unknown action is the same refusal as
 * everything else.
 */
fun Application.configureMetInPersonRoutes(
    authService: AuthService,
    service: MetInPersonService,
) {
    routing {
        route("/v1/met-in-person") {
            // The caller's own actions for one conversation, asked for when the overflow menu opens.
            post("/offers") {
                val user = call.authenticatedUser(authService)
                val request = call.receiveOrRefuse<MetInPersonOffersRequestDto>()
                call.respond(service.offers(user.id, request.threadId))
            }

            // Confirm or take back, one shape for both.
            post("/confirmations") {
                val user = call.authenticatedUser(authService)
                val request = call.receiveOrRefuse<MetInPersonConfirmationRequestDto>()
                val answer =
                    when (request.action) {
                        MetInPersonConfirmationRequestDto.ACTION_CONFIRM -> {
                            val confirm = ConfirmMetInPersonRequestDto(request.subjectUserId, request.includePartner)
                            service.confirm(user.id, request.threadId, confirm)
                        }

                        MetInPersonConfirmationRequestDto.ACTION_WITHDRAW -> {
                            service.withdraw(user.id, request.threadId, WithdrawMetInPersonRequestDto(request.subjectUserId))
                        }

                        else -> throw MessagingException(MetInPersonService.NOT_AVAILABLE)
                    }
                call.respond(answer)
            }
        }
    }
}

private suspend inline fun <reified T : Any> ApplicationCall.receiveOrRefuse(): T =
    try {
        receive<T>()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        throw MessagingException(MetInPersonService.NOT_AVAILABLE)
    }
