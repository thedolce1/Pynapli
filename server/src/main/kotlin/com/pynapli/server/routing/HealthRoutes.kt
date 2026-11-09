package com.pynapli.server.routing

import com.pynapli.shared.network.dto.DeepHealthDto
import com.pynapli.shared.network.dto.DependencyHealthDto
import com.pynapli.shared.network.dto.HealthDto
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

/** Takes only what it uses, so tests can mount it without building the whole dependency graph. */
fun Application.configureHealthRoutes(
    environment: String,
    checkDependencies: suspend () -> List<DependencyHealthDto>,
) {
    routing {
        get("/health") {
            call.respond(
                HealthDto(
                    status = "ok",
                    service = "pynapli-api",
                    environment = environment,
                ),
            )
        }

        get("/health/deep") {
            val dependencyStatuses = checkDependencies()
            val isHealthy = dependencyStatuses.all { it.status == "ok" }
            val response =
                DeepHealthDto(
                    status = if (isHealthy) "ok" else "degraded",
                    service = "pynapli-api",
                    environment = environment,
                    dependencies = dependencyStatuses,
                )

            call.respond(
                status = if (isHealthy) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable,
                message = response,
            )
        }
    }
}
