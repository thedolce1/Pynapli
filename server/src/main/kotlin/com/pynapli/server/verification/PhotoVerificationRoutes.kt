package com.pynapli.server.verification

import com.pynapli.server.auth.AuthService
import com.pynapli.server.auth.authenticatedUser
import com.pynapli.shared.network.dto.CreatePhotoVerificationUploadRequestDto
import com.pynapli.shared.network.dto.SubmitPhotoVerificationRequestDto
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing

/**
 * Selfie photo verification for the signed-in user. Every call acts on the token-derived user only;
 * challenge, upload and submit are rate-limited in [PhotoVerificationService].
 */
fun Application.configurePhotoVerificationRoutes(
    authService: AuthService,
    photoVerificationService: PhotoVerificationService,
) {
    routing {
        route("/v1/verification/photo") {
            get {
                val user = call.authenticatedUser(authService)
                call.respond(photoVerificationService.status(user.id))
            }

            post("/challenge") {
                val user = call.authenticatedUser(authService)
                call.respond(photoVerificationService.requestChallenge(user.id))
            }

            post("/uploads") {
                val user = call.authenticatedUser(authService)
                val request = call.receive<CreatePhotoVerificationUploadRequestDto>()
                call.respond(photoVerificationService.createSelfieUpload(user.id, request))
            }

            post("/submit") {
                val user = call.authenticatedUser(authService)
                val request = call.receive<SubmitPhotoVerificationRequestDto>()
                call.respond(photoVerificationService.submit(user.id, request))
            }
        }
    }
}
