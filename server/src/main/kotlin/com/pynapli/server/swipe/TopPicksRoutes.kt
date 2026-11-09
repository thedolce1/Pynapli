package com.pynapli.server.swipe

import com.pynapli.server.auth.AuthService
import com.pynapli.server.auth.authenticatedUser
import com.pynapli.server.profiling.RequestProfilerFactory
import com.pynapli.server.profiling.profilerContextElement
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.withContext

fun Application.configureTopPicksRoutes(
    authService: AuthService,
    topPicksService: TopPicksService,
    requestProfilerFactory: RequestProfilerFactory = RequestProfilerFactory.disabled(),
) {
    routing {
        get("/v1/swipe/picks") {
            val profiler = requestProfilerFactory.start(call)
            val response =
                withContext(profilerContextElement(profiler)) {
                    profiler.measure("swipe.route.picks.total") {
                        val user =
                            profiler.measure("swipe.route.picks.auth") {
                                call.authenticatedUser(authService)
                            }
                        profiler.measure("swipe.service.picks.total") {
                            topPicksService.getCurrentPicks(user.id)
                        }
                    }
                }
            profiler.appendHeaders(call)
            call.respond(response)
        }
    }
}
