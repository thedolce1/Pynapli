package com.pynapli.server.safety

import com.pynapli.server.auth.AuthException
import com.pynapli.server.auth.AuthService
import com.pynapli.shared.network.dto.BlockUserRequestDto
import com.pynapli.shared.network.dto.CreateReportRequestDto
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.request.receiveNullable
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing

fun Application.configureSafetyRoutes(
    authService: AuthService?,
    blockService: BlockService?,
    hiddenProfileService: HiddenProfileService?,
    reportService: ReportService?
) {
    if (authService == null || blockService == null || hiddenProfileService == null || reportService == null) return

    routing {
        get("/v1/blocks") {
            val user = authService.authenticate(call.requiredBearerToken())
            call.respond(blockService.listBlocks(user.id))
        }

        post("/v1/blocks/{userId}") {
            val user = authService.authenticate(call.requiredBearerToken())
            val targetUserId = call.parameters["userId"] ?: throw SafetyException("INVALID_BLOCK_TARGET")
            val request = call.receiveNullable<BlockUserRequestDto>() ?: BlockUserRequestDto()
            call.respond(blockService.blockUser(user.id, targetUserId, request.reason))
        }

        delete("/v1/blocks/{userId}") {
            val user = authService.authenticate(call.requiredBearerToken())
            val targetUserId = call.parameters["userId"] ?: throw SafetyException("INVALID_BLOCK_TARGET")
            blockService.unblockUser(user.id, targetUserId)
            call.respond(HttpStatusCode.NoContent)
        }

        get("/v1/hidden-profiles") {
            val user = authService.authenticate(call.requiredBearerToken())
            call.respond(hiddenProfileService.listHiddenProfiles(user.id))
        }

        post("/v1/hidden-profiles/{userId}") {
            val user = authService.authenticate(call.requiredBearerToken())
            val targetUserId = call.parameters["userId"] ?: throw SafetyException("INVALID_HIDDEN_PROFILE_TARGET")
            call.respond(hiddenProfileService.hideProfile(user.id, targetUserId))
        }

        delete("/v1/hidden-profiles/{userId}") {
            val user = authService.authenticate(call.requiredBearerToken())
            val targetUserId = call.parameters["userId"] ?: throw SafetyException("INVALID_HIDDEN_PROFILE_TARGET")
            hiddenProfileService.unhideProfile(user.id, targetUserId)
            call.respond(HttpStatusCode.NoContent)
        }

        post("/v1/reports") {
            val user = authService.authenticate(call.requiredBearerToken())
            val request = call.receive<CreateReportRequestDto>()
            call.respond(reportService.submitReport(user.id, request))
        }
    }
}

private fun ApplicationCall.requiredBearerToken(): String {
    val value = request.headers[HttpHeaders.Authorization] ?: throw AuthException("UNAUTHENTICATED")
    if (!value.startsWith("Bearer ", ignoreCase = true)) throw AuthException("UNAUTHENTICATED")
    return value.substringAfter("Bearer ").trim().takeIf { it.isNotEmpty() } ?: throw AuthException("UNAUTHENTICATED")
}
