package com.pynapli.server.verification

import com.pynapli.server.auth.AuthService
import com.pynapli.server.auth.authenticatedUser
import com.pynapli.server.config.ServerConfig
import com.pynapli.server.moderation.requireAdminActor
import com.pynapli.shared.network.dto.AgeVerificationCallbackRequestDto
import com.pynapli.shared.network.dto.StartAgeVerificationRequestDto
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

fun Application.configureAgeVerificationRoutes(
    authService: AuthService,
    ageVerificationService: AgeVerificationService,
) {
    routing {
        get("/v1/verification/requirements") {
            val user = call.authenticatedUser(authService)
            call.respond(
                ageVerificationService.requirements(
                    userId = user.id,
                    market = call.request.queryParameters["market"],
                ),
            )
        }

        get("/v1/verification/status") {
            val user = call.authenticatedUser(authService)
            call.respond(ageVerificationService.status(user.id))
        }

        post("/v1/verification/start") {
            val user = call.authenticatedUser(authService)
            val request = call.receive<StartAgeVerificationRequestDto>()
            call.respond(ageVerificationService.start(user.id, request))
        }
    }
}

fun Application.configureAgeVerificationAdminRoutes(
    config: ServerConfig,
    ageVerificationService: AgeVerificationService,
) {
    val adminApiToken = config.adminApiToken ?: return

    routing {
        route("/v1/admin/verification/age") {
            post("/{verificationId}/review") {
                val actor = call.requireAdminActor(adminApiToken)
                val verificationId = call.parameters["verificationId"] ?: throw VerificationException("INVALID_VERIFICATION_ID")
                val request = call.receive<AdminAgeVerificationReviewRequestDto>()
                call.respond(ageVerificationService.review(verificationId, request, actor.actorId))
            }
        }
    }
}

const val AGE_VERIFICATION_TIMESTAMP_HEADER = "X-Pynapli-Timestamp"
const val AGE_VERIFICATION_SIGNATURE_HEADER = "X-Pynapli-Signature"

/**
 * `POST /v1/verification/callback`: the age-verification provider reports an outcome. It
 * authenticates with its own secret (PYNAPLI_AGE_VERIFICATION_CALLBACK_SECRET), never the admin
 * token, so a provider (or a leak on its side) holds no admin power. Each call carries
 * [AGE_VERIFICATION_TIMESTAMP_HEADER] (unix seconds) and [AGE_VERIFICATION_SIGNATURE_HEADER] =
 * `v1=` + hex HMAC-SHA256 of `"<timestamp>.<raw body>"`; signatures older or newer than five minutes
 * are refused, which bounds replay. Not served at all without a secret.
 */
fun Application.configureAgeVerificationCallbackRoute(
    config: ServerConfig,
    ageVerificationService: AgeVerificationService,
    nowSeconds: () -> Long = { Instant.now().epochSecond },
) {
    val secret = config.ageVerificationCallbackSecret?.takeIf { it.isNotBlank() } ?: return
    val actorId = "provider:" + (config.ageVerificationProvider ?: "age_verification")
    routing {
        post("/v1/verification/callback") {
            val body = call.receiveText()
            val valid =
                AgeVerificationCallbackSignature.verify(
                    secret = secret,
                    timestampHeader = call.request.headers[AGE_VERIFICATION_TIMESTAMP_HEADER],
                    signatureHeader = call.request.headers[AGE_VERIFICATION_SIGNATURE_HEADER],
                    body = body,
                    nowSeconds = nowSeconds(),
                )
            if (!valid) throw VerificationException("INVALID_CALLBACK_SIGNATURE")
            val request =
                runCatching { callbackJson.decodeFromString(AgeVerificationCallbackRequestDto.serializer(), body) }
                    .getOrElse { throw VerificationException("INVALID_VERIFICATION_CALLBACK") }
            call.respond(ageVerificationService.callback(request, actorId, "Provider callback"))
        }
    }
}

/** Signing scheme for provider callbacks; [sign] is what the provider computes. */
object AgeVerificationCallbackSignature {
    private const val VERSION_PREFIX = "v1="
    private const val MAX_SKEW_SECONDS = 300L

    fun sign(
        secret: String,
        timestampSeconds: Long,
        body: String,
    ): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val digest = mac.doFinal("$timestampSeconds.$body".toByteArray(Charsets.UTF_8))
        return VERSION_PREFIX + digest.joinToString("") { "%02x".format(it) }
    }

    fun verify(
        secret: String,
        timestampHeader: String?,
        signatureHeader: String?,
        body: String,
        nowSeconds: Long,
    ): Boolean {
        val timestamp = timestampHeader?.trim()?.toLongOrNull() ?: return false
        val presented = signatureHeader?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        if (kotlin.math.abs(nowSeconds - timestamp) > MAX_SKEW_SECONDS) return false
        return MessageDigest.isEqual(sign(secret, timestamp, body).toByteArray(), presented.toByteArray())
    }
}

private val callbackJson = Json { ignoreUnknownKeys = true }
