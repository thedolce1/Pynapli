package com.pynapli.server.auth

import com.pynapli.shared.network.dto.EmailLoginRequestDto
import com.pynapli.shared.network.dto.EmailSignupRequestDto
import com.pynapli.shared.network.dto.LogoutResponseDto
import com.pynapli.shared.network.dto.RefreshSessionRequestDto
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing

fun Application.configureAuthRoutes(authService: AuthService?) {
    if (authService == null) return

    routing {
        route("/v1/auth") {
            post("/email/signup") {
                call.respond(
                    authService.signupEmail(
                        request = call.receive(),
                    ),
                )
            }

            post("/email/login") {
                call.respond(
                    authService.loginEmail(
                        request = call.receive(),
                    ),
                )
            }

            post("/refresh") {
                val request = call.receive<RefreshSessionRequestDto>()
                call.respond(authService.refresh(request.refreshToken))
            }

            post("/logout") {
                val token = call.bearerTokenOrNull() ?: throw AuthException("UNAUTHENTICATED")
                call.respond(LogoutResponseDto(loggedOut = authService.logout(token)))
            }
        }
    }
}

private suspend fun AuthService.signupEmail(request: EmailSignupRequestDto) =
    signupEmail(
        email = request.email,
        password = request.password,
        birthdate = request.birthdate,
        confirmedAdult = request.confirmedAdult,
        acceptedTerms = request.acceptedTerms,
        acceptedPrivacy = request.acceptedPrivacy,
        deviceId = request.deviceId,
        platform = request.platform,
        appVersion = request.appVersion,
    )

private suspend fun AuthService.loginEmail(request: EmailLoginRequestDto) =
    loginEmail(
        email = request.email,
        password = request.password,
        deviceId = request.deviceId,
        platform = request.platform,
        appVersion = request.appVersion,
    )

fun authStatusFor(code: String): HttpStatusCode =
    when (code) {
        "EMAIL_ALREADY_EXISTS" -> HttpStatusCode.Conflict
        "INVALID_CREDENTIALS", "INVALID_REFRESH_TOKEN", "UNAUTHENTICATED" -> HttpStatusCode.Unauthorized
        "ACCOUNT_NOT_ACTIVE" -> HttpStatusCode.Forbidden
        "RATE_LIMITED" -> HttpStatusCode.TooManyRequests
        "INVALID_EMAIL",
        "PASSWORD_TOO_SHORT",
        "INVALID_BIRTHDATE",
        "BIRTHDATE_REQUIRED",
        "AGE_RESTRICTED",
        "ADULT_CONFIRMATION_REQUIRED",
        "TERMS_ACCEPTANCE_REQUIRED",
        "PRIVACY_ACCEPTANCE_REQUIRED",
        -> HttpStatusCode.BadRequest
        else -> HttpStatusCode.BadRequest
    }
