package com.pynapli.server.swipe

import com.pynapli.server.auth.AuthService
import com.pynapli.server.auth.authenticatedUser
import com.pynapli.server.profiling.RequestProfilerFactory
import com.pynapli.server.profiling.profilerContextElement
import com.pynapli.shared.network.dto.SwipeActionRequestDto
import com.pynapli.shared.network.dto.SwipeDeckRequestDto
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.request.receiveNullable
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.withContext

fun Application.configureSwipeRoutes(
    authService: AuthService,
    swipeService: SwipeService,
    requestProfilerFactory: RequestProfilerFactory = RequestProfilerFactory.disabled(),
) {
    routing {
        post("/v1/swipe/deck/query") {
            val profiler = requestProfilerFactory.start(call)
            val response =
                withContext(profilerContextElement(profiler)) {
                    profiler.measure("swipe.route.deck.total") {
                        val user =
                            profiler.measure("swipe.route.deck.auth") {
                                call.authenticatedUser(authService)
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
                                call.authenticatedUser(authService)
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
                                call.authenticatedUser(authService)
                            }
                        profiler.measure("swipe.service.rewind.total") {
                            swipeService.rewind(user.id)
                        }
                    }
                }
            profiler.appendHeaders(call)
            call.respond(response)
        }

        post("/v1/swipe/like-back") {
            val user = call.authenticatedUser(authService)
            val request = call.receive<com.pynapli.shared.network.dto.LikeBackRequestDto>()
            call.respond(swipeService.likeBack(user.id, request))
        }

        get("/v1/matches") {
            val user = call.authenticatedUser(authService)
            call.respond(swipeService.listMatches(user.id, call.request.queryParameters["cursor"]))
        }
    }
}
