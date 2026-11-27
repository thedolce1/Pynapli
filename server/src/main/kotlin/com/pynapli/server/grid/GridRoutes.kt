package com.pynapli.server.grid

import com.pynapli.server.auth.AuthService
import com.pynapli.server.auth.authenticatedUser
import com.pynapli.server.profiling.RequestProfilerFactory
import com.pynapli.server.profiling.profilerContextElement
import com.pynapli.shared.network.dto.GridQueryRequestDto
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.withContext

fun Application.configureGridRoutes(
    authService: AuthService,
    gridService: GridService,
    requestProfilerFactory: RequestProfilerFactory = RequestProfilerFactory.disabled(),
) {
    routing {
        post("/v1/grid/query") {
            val profiler = requestProfilerFactory.start(call)
            val response =
                withContext(profilerContextElement(profiler)) {
                    profiler.measure("grid.route.total") {
                        val user =
                            profiler.measure("grid.route.auth") {
                                call.authenticatedUser(authService)
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
        // Plan G2-4: the filter sheet's "Show N people". The body is the Grid request, never a query
        // string (filters can be sensitive). Same auth, filters and visibility as /v1/grid/query.
        post("/v1/grid/count") {
            val user = call.authenticatedUser(authService)
            val request = call.receive<GridQueryRequestDto>()
            call.respond(gridService.countGrid(user.id, request))
        }
    }
}
