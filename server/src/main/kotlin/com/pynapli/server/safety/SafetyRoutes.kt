package com.pynapli.server.safety

import com.pynapli.server.auth.AuthService
import com.pynapli.server.auth.authenticatedUser
import com.pynapli.shared.network.dto.BlockUserRequestDto
import com.pynapli.shared.network.dto.CreateReportRequestDto
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.request.receiveNullable
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing

fun Application.configureSafetyRoutes(
    authService: AuthService,
    blockService: BlockService,
    hiddenProfileService: HiddenProfileService,
    reportService: ReportService,
) {
    routing {
        get("/v1/blocks") {
            val user = call.authenticatedUser(authService)
            call.respond(blockService.listBlocks(user.id))
        }

        post("/v1/blocks/{userId}") {
            val user = call.authenticatedUser(authService)
            val targetUserId = call.parameters["userId"] ?: throw SafetyException("INVALID_BLOCK_TARGET")
            val request = call.receiveNullable<BlockUserRequestDto>() ?: BlockUserRequestDto()
            call.respond(blockService.blockUser(user.id, targetUserId, request.reason))
        }

        delete("/v1/blocks/{userId}") {
            val user = call.authenticatedUser(authService)
            val targetUserId = call.parameters["userId"] ?: throw SafetyException("INVALID_BLOCK_TARGET")
            blockService.unblockUser(user.id, targetUserId)
            call.respond(HttpStatusCode.NoContent)
        }

        get("/v1/hidden-profiles") {
            val user = call.authenticatedUser(authService)
            call.respond(hiddenProfileService.listHiddenProfiles(user.id))
        }

        post("/v1/hidden-profiles/{userId}") {
            val user = call.authenticatedUser(authService)
            val targetUserId = call.parameters["userId"] ?: throw SafetyException("INVALID_HIDDEN_PROFILE_TARGET")
            call.respond(hiddenProfileService.hideProfile(user.id, targetUserId))
        }

        delete("/v1/hidden-profiles/{userId}") {
            val user = call.authenticatedUser(authService)
            val targetUserId = call.parameters["userId"] ?: throw SafetyException("INVALID_HIDDEN_PROFILE_TARGET")
            hiddenProfileService.unhideProfile(user.id, targetUserId)
            call.respond(HttpStatusCode.NoContent)
        }

        post("/v1/reports") {
            val user = call.authenticatedUser(authService)
            val request = call.receive<CreateReportRequestDto>()
            call.respond(reportService.submitReport(user.id, request))
        }

        get("/v1/reports") {
            val user = call.authenticatedUser(authService)
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 25
            call.respond(reportService.listReports(user.id, limit))
        }

        get("/v1/support/contact") {
            val user = call.authenticatedUser(authService)
            call.respond(reportService.supportContact(user.id))
        }
    }
}
