package com.pynapli.server.account

import com.pynapli.server.auth.AuthService
import com.pynapli.server.auth.authenticatedUser
import com.pynapli.server.auth.requiredBearerToken
import com.pynapli.shared.network.dto.DeleteAccountRequestDto
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing

fun Application.configureAccountLifecycleRoutes(
    authService: AuthService,
    accountLifecycleService: AccountLifecycleService,
) {
    routing {
        get("/v1/account/data-export") {
            val user = call.authenticatedUser(authService)
            val latest = accountLifecycleService.latestDataExportRequest(user.id)
            if (latest == null) {
                call.respond(HttpStatusCode.NoContent)
            } else {
                call.respond(latest)
            }
        }

        post("/v1/account/data-export") {
            val user = call.authenticatedUser(authService)
            call.respond(accountLifecycleService.requestDataExport(user.id))
        }

        // Capability URL (local storage only): opened in a browser, which sends no bearer token, so the
        // random key in the link authorizes the download, like a GCS signed URL. Only the owner ever
        // sees the link (authenticated status endpoint), and it stops working when the export expires.
        get("/v1/account/data-export/{requestId}/download") {
            val requestId = call.parameters["requestId"].orEmpty()
            val key = call.request.queryParameters["key"].orEmpty()
            val archive = accountLifecycleService.downloadExport(requestId, key)
            call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"${archive.filename}\"")
            call.respondBytes(archive.bytes, ContentType.parse(archive.contentType))
        }

        post("/v1/account/delete") {
            val token = call.requiredBearerToken()
            // Deleting the account is not activity, and it ends presence at once (privacy review F3).
            val user = authService.authenticateWithoutActivity(token)
            val request = call.receive<DeleteAccountRequestDto>()
            val response = accountLifecycleService.deleteAccount(user.id, request.confirmation)
            authService.invalidateCachedSessionsForUser(user.id)
            authService.endPresence(user.id)
            call.respond(response)
        }
    }
}
