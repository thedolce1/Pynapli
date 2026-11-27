package com.pynapli.server.auth.social

import com.pynapli.server.auth.AuthService
import com.pynapli.server.auth.requiredBearerToken
import com.pynapli.shared.network.dto.LinkSocialIdentityRequestDto
import com.pynapli.shared.network.dto.SocialSignInRequestDto
import com.pynapli.shared.network.dto.UnlinkedIdentityDto
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing

/**
 * Continue with Google / Apple (API_CONTRACT_SPEC "Section 5 contract"). Always installed: with the
 * flag off, config answers false/false and every token route answers 404 SOCIAL_SIGN_IN_UNAVAILABLE.
 * The suspended-account appeal through a provider sign-in is in AppealRoutes.
 */
fun Application.configureSocialAuthRoutes(
    authService: AuthService,
    socialAuthService: SocialAuthService,
) {
    routing {
        get("/v1/auth/social/config") {
            call.respond(socialAuthService.config())
        }

        post("/v1/auth/social/nonce") {
            call.respond(socialAuthService.issueNonce())
        }

        post("/v1/auth/social/{provider}") {
            val request = call.receive<SocialSignInRequestDto>()
            call.respond(socialAuthService.signIn(call.parameters["provider"], request))
        }

        // Settings: the account's sign-in methods. Managing them is not activity (no presence refresh).
        get("/v1/auth/identities") {
            val user = authService.authenticateWithoutActivity(call.requiredBearerToken())
            call.respond(socialAuthService.identities(user.id))
        }

        post("/v1/auth/identities/{provider}") {
            val user = authService.authenticateWithoutActivity(call.requiredBearerToken())
            val request = call.receive<LinkSocialIdentityRequestDto>()
            call.respond(socialAuthService.link(user.id, call.parameters["provider"], request))
        }

        delete("/v1/auth/identities/{provider}") {
            val user = authService.authenticateWithoutActivity(call.requiredBearerToken())
            call.respond(UnlinkedIdentityDto(unlinked = socialAuthService.unlink(user.id, call.parameters["provider"])))
        }
    }
}
