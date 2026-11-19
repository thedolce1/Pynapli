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
import io.ktor.server.application.ApplicationCall
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
 * only when PYNAPLI_DEV_SIGNUP_SHORTCUTS_ENABLED=true (off by default), PYNAPLI_ENV is local and the
 * local file mailer is wired; startup validation refuses the flag in production-like environments.
 * Even then they answer 404 to anything but a direct loopback request ([isDirectLoopbackRequest]),
 * so a tunnel or proxy in front of a local server never reaches them.
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
                if (!call.isDirectLoopbackRequest()) {
                    call.respond(HttpStatusCode.NotFound)
                    return@post
                }
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
            // Codes only: it never says whether an address got the "already registered" notice.
            post("/v1/dev/auth/email/signup/latest-code") {
                if (!call.isDirectLoopbackRequest()) {
                    call.respond(HttpStatusCode.NotFound)
                    return@post
                }
                val request = call.receive<DevSignupCodeRequestDto>()
                call.respond(DevSignupCodeDto(code = mailbox.latestCodeFor(request.email)))
            }
        }
    }
}

/**
 * The dev routes serve only a request made on this machine, straight to the server: the socket peer
 * is a loopback address AND the request carries no proxy or tunnel header. A tunnel (cloudflared
 * runs on this machine, so its peer IS loopback) or any reverse proxy adds forwarding headers, so its
 * requests are refused even though they arrive from 127.0.0.1. The peer is the socket address, never
 * a header, so it can't be spoofed; a client could only add headers, which makes it more likely to
 * be refused, never less.
 */
internal fun ApplicationCall.isDirectLoopbackRequest(): Boolean =
    isDirectLoopbackRequest(
        remoteAddress = request.local.remoteAddress,
        headerNames = request.headers.names(),
    )

internal fun isDirectLoopbackRequest(
    remoteAddress: String,
    headerNames: Collection<String>,
): Boolean {
    val peer = remoteAddress.trim().removePrefix("/").lowercase()
    if (peer !in LOOPBACK_PEERS && !peer.startsWith("127.")) return false
    return headerNames.none { name ->
        val header = name.lowercase()
        header in FORWARDING_HEADERS || FORWARDING_HEADER_PREFIXES.any { header.startsWith(it) }
    }
}

// "localhost" is what Ktor's in-process test engine reports for its own (loopback) peer.
private val LOOPBACK_PEERS = setOf("127.0.0.1", "::1", "0:0:0:0:0:0:0:1", "[::1]", "localhost")

private val FORWARDING_HEADERS =
    setOf(
        "forwarded",
        "via",
        "x-real-ip",
        "true-client-ip",
        "x-client-ip",
        "x-cluster-client-ip",
        "fly-client-ip",
        "fastly-client-ip",
        "cdn-loop",
        "x-original-forwarded-for",
    )

// x-forwarded-for/-host/-proto/-port, and Cloudflare's cf-connecting-ip, cf-ray, cf-visitor, cf-ipcountry...
private val FORWARDING_HEADER_PREFIXES = listOf("x-forwarded-", "cf-")
