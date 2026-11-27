package com.pynapli.server.safety

import com.pynapli.server.auth.AuthException
import com.pynapli.server.auth.AuthService
import com.pynapli.server.auth.authenticatedUser
import com.pynapli.server.auth.social.SocialAuthService
import com.pynapli.shared.network.dto.AccountAppealRequestDto
import com.pynapli.shared.network.dto.CreateAppealRequestDto
import com.pynapli.shared.network.dto.SocialAccountAppealRequestDto
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing

fun Application.configureAppealRoutes(
    authService: AuthService,
    appealService: AppealService,
    trustedProxyHops: Int = 1,
    socialAuthService: SocialAuthService? = null,
) {
    routing {
        post("/v1/appeals") {
            val user = call.authenticatedUser(authService)
            val request = call.receive<CreateAppealRequestDto>()
            call.respond(appealService.submitAppeal(user.id, request))
        }

        get("/v1/appeals") {
            val user = call.authenticatedUser(authService)
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 25
            call.respond(appealService.listAppeals(user.id, limit))
        }

        // A suspended or banned account can't sign in, so it appeals with its email and password
        // instead of a session. Only account-level appeals go through here.
        post("/v1/auth/account-appeal") {
            val request = call.receive<AccountAppealRequestDto>()
            val identity = authService.verifyCredentials(request.email, request.password, call.clientIp(trustedProxyHops))
            val actionType =
                when (identity.userStatus) {
                    "suspended" -> "account_suspension"
                    "banned" -> "account_ban"
                    else -> throw AuthException("ACCOUNT_NOT_RESTRICTED")
                }
            call.respond(
                appealService.submitAppeal(
                    identity.userId,
                    CreateAppealRequestDto(actionType = actionType, targetType = "account", reason = request.reason),
                ),
            )
        }

        // The same appeal for an account that signs in with Google or Apple: a fresh provider sign-in
        // (ID token + server nonce) proves the identity instead of a password.
        socialAuthService?.let { social ->
            post("/v1/auth/account-appeal/social/{provider}") {
                val request = call.receive<SocialAccountAppealRequestDto>()
                val identity = social.identityForAppeal(call.parameters["provider"], request.idToken, request.nonce)
                val actionType =
                    when (identity.userStatus) {
                        "suspended" -> "account_suspension"
                        "banned" -> "account_ban"
                        else -> throw AuthException("ACCOUNT_NOT_RESTRICTED")
                    }
                call.respond(
                    appealService.submitAppeal(
                        identity.userId,
                        CreateAppealRequestDto(actionType = actionType, targetType = "account", reason = request.reason),
                    ),
                )
            }
        }

        post("/v1/appeals/{appealId}/withdraw") {
            val user = call.authenticatedUser(authService)
            val appealId = call.parameters["appealId"] ?: throw SafetyException("INVALID_APPEAL_ID")
            call.respond(appealService.withdrawAppeal(user.id, appealId))
        }
    }
}
