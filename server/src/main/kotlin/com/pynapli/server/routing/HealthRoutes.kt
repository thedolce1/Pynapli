package com.pynapli.server.routing

import com.pynapli.server.dependencies.ServerDependencies
import com.pynapli.shared.network.dto.DeepHealthDto
import com.pynapli.shared.network.dto.HealthDto
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

fun Application.configureHealthRoutes(dependencies: ServerDependencies) {
    routing {
        get("/health") {
            call.respond(
                HealthDto(
                    status = "ok",
                    service = "pynapli-api",
                    environment = dependencies.config.environment
                )
            )
        }

        get("/health/deep") {
            val dependencyStatuses = dependencies.checkAll()
            val isHealthy = dependencyStatuses.all { it.status == "ok" }
            val response = DeepHealthDto(
                status = if (isHealthy) "ok" else "degraded",
                service = "pynapli-api",
                environment = dependencies.config.environment,
                dependencies = dependencyStatuses
            )

            call.respond(
                status = if (isHealthy) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable,
                message = response
            )
        }
    }
}
