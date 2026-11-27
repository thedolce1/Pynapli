package com.pynapli.server.auth

import com.pynapli.server.safety.clientIp
import com.pynapli.shared.network.dto.EmailLoginRequestDto
import com.pynapli.shared.network.dto.LogoutResponseDto
import com.pynapli.shared.network.dto.PasswordChangeDto
import com.pynapli.shared.network.dto.PasswordChangedDto
import com.pynapli.shared.network.dto.PasswordResetDto
import com.pynapli.shared.network.dto.PasswordResetRequestDto
import com.pynapli.shared.network.dto.PasswordResetRequestedDto
import com.pynapli.shared.network.dto.RefreshSessionRequestDto
import com.pynapli.shared.network.dto.SignedOutDevicesDto
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing

fun Application.configureAuthRoutes(
    authService: AuthService,
    passwordRecoveryService: PasswordRecoveryService? = null,
    signedInDevicesService: SignedInDevicesService? = null,
    trustedProxyHops: Int = 1,
) {
    routing {
        route("/v1/auth") {
            // Email sign-up lives in EmailSignupRoutes (verified, membership-private).

            post("/email/login") {
                call.respond(
                    authService.loginEmail(
                        request = call.receive(),
                        clientIp = call.clientIp(trustedProxyHops),
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

            signedInDevicesService?.let { devices ->
                get("/sessions") {
                    val token = call.bearerTokenOrNull() ?: throw AuthException("UNAUTHENTICATED")
                    val user = authService.authenticateWithoutActivity(token)
                    call.respond(devices.list(user.id, token))
                }

                // Signing sessions out is not activity (privacy review F3): no presence refresh.
                delete("/sessions/{sessionId}") {
                    val token = call.bearerTokenOrNull() ?: throw AuthException("UNAUTHENTICATED")
                    val user = authService.authenticateWithoutActivity(token)
                    devices.signOut(user.id, token, call.parameters["sessionId"].orEmpty())
                    call.respond(SignedOutDevicesDto(signedOut = 1))
                }

                post("/sessions/sign-out-others") {
                    val token = call.bearerTokenOrNull() ?: throw AuthException("UNAUTHENTICATED")
                    val user = authService.authenticateWithoutActivity(token)
                    call.respond(SignedOutDevicesDto(signedOut = devices.signOutOthers(user.id, token)))
                }
            }

            passwordRecoveryService?.let { recovery ->
                // Same answer whether or not the email has an account.
                post("/password/reset-request") {
                    val request = call.receive<PasswordResetRequestDto>()
                    recovery.requestReset(request.email, request.deviceId)
                    call.respond(PasswordResetRequestedDto(sent = true))
                }

                post("/password/reset") {
                    val request = call.receive<PasswordResetDto>()
                    recovery.resetPassword(request.email, request.code, request.newPassword)
                    call.respond(PasswordChangedDto(changed = true))
                }

                post("/password/change") {
                    val token = call.bearerTokenOrNull() ?: throw AuthException("UNAUTHENTICATED")
                    val user = authService.authenticateWithoutActivity(token)
                    val request = call.receive<PasswordChangeDto>()
                    recovery.changePassword(user.id, token, request.currentPassword, request.newPassword)
                    call.respond(PasswordChangedDto(changed = true))
                }
            }
        }
    }
}

private suspend fun AuthService.loginEmail(
    request: EmailLoginRequestDto,
    clientIp: String,
) = loginEmail(
    email = request.email,
    password = request.password,
    deviceId = request.deviceId,
    platform = request.platform,
    appVersion = request.appVersion,
    clientIp = clientIp,
)

fun authStatusFor(code: String): HttpStatusCode =
    when (code) {
        "EMAIL_ALREADY_EXISTS" -> HttpStatusCode.Conflict
        "INVALID_CREDENTIALS", "INVALID_REFRESH_TOKEN", "UNAUTHENTICATED" -> HttpStatusCode.Unauthorized
        "ACCOUNT_NOT_ACTIVE" -> HttpStatusCode.Forbidden
        "ACCOUNT_NOT_RESTRICTED" -> HttpStatusCode.Conflict
        "PASSWORD_RESET_UNAVAILABLE", "SIGNUP_UNAVAILABLE" -> HttpStatusCode.ServiceUnavailable
        "WRONG_CURRENT_PASSWORD" -> HttpStatusCode.Forbidden
        "SESSION_NOT_FOUND" -> HttpStatusCode.NotFound
        "CANNOT_SIGN_OUT_CURRENT_SESSION" -> HttpStatusCode.Conflict
        "RATE_LIMITED" -> HttpStatusCode.TooManyRequests
        // Continue with Google / Apple (API_CONTRACT_SPEC "Section 5 contract").
        "INVALID_SOCIAL_TOKEN" -> HttpStatusCode.Unauthorized
        "SOCIAL_SIGN_IN_UNAVAILABLE", "SOCIAL_IDENTITY_NOT_LINKED", "SOCIAL_ACCOUNT_NOT_FOUND" -> HttpStatusCode.NotFound
        "SOCIAL_SIGNUP_NEEDS_AGE_GATE" -> HttpStatusCode.UnprocessableEntity
        "SOCIAL_EMAIL_HAS_ACCOUNT", "SOCIAL_IDENTITY_IN_USE", "SOCIAL_PROVIDER_ALREADY_LINKED", "LAST_SIGN_IN_METHOD" -> HttpStatusCode.Conflict
        "SOCIAL_SIGN_IN_TEMPORARILY_UNAVAILABLE" -> HttpStatusCode.ServiceUnavailable
        "INVALID_EMAIL",
        "PASSWORD_TOO_SHORT",
        "PASSWORD_TOO_COMMON",
        "INVALID_BIRTHDATE",
        "BIRTHDATE_REQUIRED",
        "AGE_RESTRICTED",
        "ADULT_CONFIRMATION_REQUIRED",
        "TERMS_ACCEPTANCE_REQUIRED",
        "PRIVACY_ACCEPTANCE_REQUIRED",
        "INVALID_SIGNUP_CODE",
        -> HttpStatusCode.BadRequest
        else -> HttpStatusCode.BadRequest
    }
