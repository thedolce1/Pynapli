package com.pynapli.server.auth

import com.pynapli.server.safety.clientIp
import com.pynapli.shared.network.dto.DevSignupCodeDto
import com.pynapli.shared.network.dto.DevSignupCodeRequestDto
import com.pynapli.shared.network.dto.EmailSignupCodeResentDto
import com.pynapli.shared.network.dto.EmailSignupRequestDto
import com.pynapli.shared.network.dto.EmailSignupResendRequestDto
import com.pynapli.shared.network.dto.EmailSignupVerifyRequestDto
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing

/**
 * Email sign-up (ONBOARDING_AUTH_SPEC, "Membership privacy"). The sign-up and resend answers are
 * the same for every syntactically valid request, whether or not the address has an account; the
 * session only comes from verify.
 *
 * [devMailbox] turns on two local-development-only helpers under `/v1/dev/auth/...` (create a
 * verified account at once; read the last code the local file mailer "sent"). The caller passes it
 * only when `devSignupShortcutsEnabled` is on, PYNAPLI_ENV is local and the local file mailer is
 * wired; startup validation refuses the flag in production-like environments. Otherwise the paths
 * don't exist (404).
 */
fun Application.configureEmailSignupRoutes(
    signupService: EmailSignupService,
    trustedProxyHops: Int = 1,
    devMailbox: LocalFileSignupMailer? = null,
) {
    routing {
        post("/v1/auth/email/signup") {
            val request = call.receive<EmailSignupRequestDto>()
            call.respond(
                HttpStatusCode.Accepted,
                signupService.startSignup(
                    email = request.email,
                    password = request.password,
                    birthdate = request.birthdate,
                    confirmedAdult = request.confirmedAdult,
                    acceptedTerms = request.acceptedTerms,
                    acceptedPrivacy = request.acceptedPrivacy,
                    deviceId = request.deviceId,
                    clientIp = call.clientIp(trustedProxyHops),
                ),
            )
        }

        post("/v1/auth/email/signup/resend") {
            val request = call.receive<EmailSignupResendRequestDto>()
            signupService.resendCode(
                email = request.email,
                signupToken = request.signupToken,
                deviceId = request.deviceId,
                clientIp = call.clientIp(trustedProxyHops),
            )
            call.respond(HttpStatusCode.Accepted, EmailSignupCodeResentDto(sent = true))
        }

        post("/v1/auth/email/signup/verify") {
            val request = call.receive<EmailSignupVerifyRequestDto>()
            call.respond(
                signupService.verify(
                    email = request.email,
                    code = request.code,
                    signupToken = request.signupToken,
                    deviceId = request.deviceId,
                    platform = request.platform,
                    appVersion = request.appVersion,
                    clientIp = call.clientIp(trustedProxyHops),
                ),
            )
        }

        devMailbox?.let { mailbox ->
            // Seeds and load tests: a verified account at once, through the same account-creation
            // function as verify. 409 EMAIL_ALREADY_EXISTS for an existing address (local only).
            post("/v1/dev/auth/email/signup") {
                val request = call.receive<EmailSignupRequestDto>()
                call.respond(
                    signupService.createVerifiedAccountForLocalDev(
                        email = request.email,
                        password = request.password,
                        birthdate = request.birthdate,
                        confirmedAdult = request.confirmedAdult,
                        acceptedTerms = request.acceptedTerms,
                        acceptedPrivacy = request.acceptedPrivacy,
                        deviceId = request.deviceId,
                        platform = request.platform,
                        appVersion = request.appVersion,
                    ),
                )
            }

            // UI QA (scripts/android-onboarding-qa.ps1): the code a developer would read in
            // .pynapli-local-mail/signup-codes.txt, without depending on the server's working directory.
            post("/v1/dev/auth/email/signup/latest-code") {
                val request = call.receive<DevSignupCodeRequestDto>()
                val mail = mailbox.latestFor(request.email)
                call.respond(
                    DevSignupCodeDto(
                        code = mail?.code,
                        alreadyRegisteredNoticeSent = mail?.alreadyRegisteredNotice == true,
                    ),
                )
            }
        }
    }
}
