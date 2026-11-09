package com.pynapli.server.presence

import com.pynapli.server.auth.AuthService
import com.pynapli.server.auth.authenticatedUser
import com.pynapli.server.profiling.RequestProfilerFactory
import com.pynapli.server.profiling.profilerContextElement
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.withContext

fun Application.configurePresenceRoutes(
    authService: AuthService,
    presenceService: PresenceService,
    requestProfilerFactory: RequestProfilerFactory = RequestProfilerFactory.disabled(),
) {
    routing {
        post("/v1/presence/heartbeat") {
            val profiler = requestProfilerFactory.start(call)
            val response =
                withContext(profilerContextElement(profiler)) {
                    profiler.measure("presence.route.total") {
                        val user =
                            profiler.measure("presence.route.auth") {
                                call.authenticatedUser(authService)
                            }
                        profiler.measure("presence.service.total") {
                            presenceService.heartbeat(user.id)
                        }
                    }
                }
            profiler.appendHeaders(call)
            call.respond(response)
        }
    }
}
