package com.pynapli.server.swipe

import com.pynapli.server.auth.AuthException
import com.pynapli.server.auth.AuthService
import com.pynapli.server.profiling.RequestProfilerFactory
import com.pynapli.server.profiling.profilerContextElement
import com.pynapli.shared.network.dto.SwipeActionRequestDto
import com.pynapli.shared.network.dto.SwipeDeckRequestDto
import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.request.receiveNullable
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.withContext

fun Application.configureSwipeRoutes(
    authService: AuthService?,
    swipeService: SwipeService?,
    requestProfilerFactory: RequestProfilerFactory = RequestProfilerFactory.disabled(),
) {
    if (authService == null || swipeService == null) return

    routing {
        post("/v1/swipe/deck/query") {
            val profiler = requestProfilerFactory.start(call)
            val response =
                withContext(profilerContextElement(profiler)) {
                    profiler.measure("swipe.route.deck.total") {
                        val user =
                            profiler.measure("swipe.route.deck.auth") {
                                authService.authenticate(call.requiredBearerToken())
                            }
                        val request =
                            profiler.measure("swipe.route.deck.receive") {
                                call.receiveNullable<SwipeDeckRequestDto>() ?: SwipeDeckRequestDto()
                            }
                        profiler.measure("swipe.service.deck.total") {
                            swipeService.queryDeck(user.id, request)
                        }
                    }
                }
            profiler.appendHeaders(call)
            call.respond(response)
        }

        post("/v1/swipe/actions") {
            val profiler = requestProfilerFactory.start(call)
            val response =
                withContext(profilerContextElement(profiler)) {
                    profiler.measure("swipe.route.action.total") {
                        val user =
                            profiler.measure("swipe.route.action.auth") {
                                authService.authenticate(call.requiredBearerToken())
                            }
                        val request =
                            profiler.measure("swipe.route.action.receive") {
                                call.receive<SwipeActionRequestDto>()
                            }
                        profiler.measure("swipe.service.action.total") {
                            swipeService.submitAction(user.id, request)
                        }
                    }
                }
            profiler.appendHeaders(call)
            call.respond(response)
        }

        post("/v1/swipe/rewind") {
            val profiler = requestProfilerFactory.start(call)
            val response =
                withContext(profilerContextElement(profiler)) {
                    profiler.measure("swipe.route.rewind.total") {
                        val user =
                            profiler.measure("swipe.route.rewind.auth") {
                                authService.authenticate(call.requiredBearerToken())
                            }
                        profiler.measure("swipe.service.rewind.total") {
                            swipeService.rewind(user.id)
                        }
                    }
                }
            profiler.appendHeaders(call)
            call.respond(response)
        }

        get("/v1/matches") {
            val user = authService.authenticate(call.requiredBearerToken())
            call.respond(swipeService.listMatches(user.id))
        }
    }
}

private fun ApplicationCall.requiredBearerToken(): String {
    val value = request.headers[HttpHeaders.Authorization] ?: throw AuthException("UNAUTHENTICATED")
    if (!value.startsWith("Bearer ", ignoreCase = true)) throw AuthException("UNAUTHENTICATED")
    return value.substringAfter("Bearer ").trim().takeIf { it.isNotEmpty() } ?: throw AuthException("UNAUTHENTICATED")
}
