package com.pynapli.server.swipe

import com.pynapli.server.auth.AuthException
import com.pynapli.server.auth.AuthService
import com.pynapli.server.profiling.RequestProfilerFactory
import com.pynapli.server.profiling.profilerContextElement
import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.withContext

fun Application.configureTopPicksRoutes(
    authService: AuthService?,
    topPicksService: TopPicksService?,
    requestProfilerFactory: RequestProfilerFactory = RequestProfilerFactory.disabled()
) {
    if (authService == null || topPicksService == null) return

    routing {
        get("/v1/swipe/picks") {
            val profiler = requestProfilerFactory.start(call)
            val response = withContext(profilerContextElement(profiler)) {
                profiler.measure("swipe.route.picks.total") {
                    val user = profiler.measure("swipe.route.picks.auth") {
                        authService.authenticate(call.requiredBearerToken())
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

private fun ApplicationCall.requiredBearerToken(): String {
    val value = request.headers[HttpHeaders.Authorization] ?: throw AuthException("UNAUTHENTICATED")
    if (!value.startsWith("Bearer ", ignoreCase = true)) throw AuthException("UNAUTHENTICATED")
    return value.substringAfter("Bearer ").trim().takeIf { it.isNotEmpty() } ?: throw AuthException("UNAUTHENTICATED")
}
