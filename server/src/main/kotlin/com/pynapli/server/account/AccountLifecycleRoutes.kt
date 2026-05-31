package com.pynapli.server.account

import com.pynapli.server.auth.AuthException
import com.pynapli.server.auth.AuthService
import com.pynapli.shared.network.dto.DeleteAccountRequestDto
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing

fun Application.configureAccountLifecycleRoutes(
    authService: AuthService?,
    accountLifecycleService: AccountLifecycleService?,
) {
    if (authService == null || accountLifecycleService == null) return

    routing {
        get("/v1/account/data-export") {
            val user = authService.authenticate(call.requiredBearerToken())
            val latest = accountLifecycleService.latestDataExportRequest(user.id)
            if (latest == null) {
                call.respond(HttpStatusCode.NoContent)
            } else {
                call.respond(latest)
            }
        }

        post("/v1/account/data-export") {
            val user = authService.authenticate(call.requiredBearerToken())
            call.respond(accountLifecycleService.requestDataExport(user.id))
        }

        post("/v1/account/delete") {
            val token = call.requiredBearerToken()
            val user = authService.authenticate(token)
            val request = call.receive<DeleteAccountRequestDto>()
            val response = accountLifecycleService.deleteAccount(user.id, request.confirmation)
            authService.invalidateCachedSessionsForUser(user.id)
            call.respond(response)
        }
    }
}

private fun ApplicationCall.requiredBearerToken(): String {
    val value = request.headers[HttpHeaders.Authorization] ?: throw AuthException("UNAUTHENTICATED")
    if (!value.startsWith("Bearer ", ignoreCase = true)) throw AuthException("UNAUTHENTICATED")
    return value.substringAfter("Bearer ").trim().takeIf { it.isNotEmpty() } ?: throw AuthException("UNAUTHENTICATED")
}
