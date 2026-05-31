package com.pynapli.server.grid

import com.pynapli.server.auth.AuthException
import com.pynapli.server.auth.AuthService
import com.pynapli.server.profiling.RequestProfilerFactory
import com.pynapli.server.profiling.profilerContextElement
import com.pynapli.shared.network.dto.GridQueryRequestDto
import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.withContext

fun Application.configureGridRoutes(
    authService: AuthService?,
    gridService: GridService?,
    requestProfilerFactory: RequestProfilerFactory = RequestProfilerFactory.disabled(),
) {
    if (authService == null || gridService == null) return

    routing {
        post("/v1/grid/query") {
            val profiler = requestProfilerFactory.start(call)
            val response =
                withContext(profilerContextElement(profiler)) {
                    profiler.measure("grid.route.total") {
                        val user =
                            profiler.measure("grid.route.auth") {
                                authService.authenticate(call.requiredBearerToken())
                            }
                        val request =
                            profiler.measure("grid.route.receive") {
                                call.receive<GridQueryRequestDto>()
                            }
                        profiler.measure("grid.service.total") {
                            gridService.queryGrid(user.id, request)
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
