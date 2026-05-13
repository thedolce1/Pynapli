package com.pynapli.server.presence

import com.pynapli.server.auth.AuthException
import com.pynapli.server.auth.AuthService
import com.pynapli.server.profiling.RequestProfilerFactory
import com.pynapli.server.profiling.profilerContextElement
import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.withContext

fun Application.configurePresenceRoutes(
    authService: AuthService?,
    presenceService: PresenceService?,
    requestProfilerFactory: RequestProfilerFactory = RequestProfilerFactory.disabled()
) {
    if (authService == null || presenceService == null) return

    routing {
        post("/v1/presence/heartbeat") {
            val profiler = requestProfilerFactory.start(call)
            val response = withContext(profilerContextElement(profiler)) {
                profiler.measure("presence.route.total") {
                    val user = profiler.measure("presence.route.auth") {
                        authService.authenticate(call.requiredBearerToken())
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

private fun ApplicationCall.requiredBearerToken(): String {
    val value = request.headers[HttpHeaders.Authorization] ?: throw AuthException("UNAUTHENTICATED")
    if (!value.startsWith("Bearer ", ignoreCase = true)) throw AuthException("UNAUTHENTICATED")
    return value.substringAfter("Bearer ").trim().takeIf { it.isNotEmpty() } ?: throw AuthException("UNAUTHENTICATED")
}
