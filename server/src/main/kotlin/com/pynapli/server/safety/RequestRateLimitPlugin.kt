package com.pynapli.server.safety

import com.pynapli.server.auth.bearerTokenOrNull
import com.pynapli.server.auth.security.Sha256
import com.pynapli.server.plugins.userSafeMessage
import com.pynapli.shared.network.dto.ApiErrorDto
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.plugins.origin
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.ktor.server.response.respond
import org.slf4j.LoggerFactory

/**
 * Request-level abuse limits for the write paths that have no per-feature limit of their own.
 *
 * Counters live in Redis (via [RateLimitService]), so every replica shares them. A rule is keyed
 * by the caller's session (a hash of the bearer token, never the token) or, for sign-in-type
 * endpoints that run before there is a session, by client IP. Over the limit the request is
 * answered 429 RATE_LIMITED with Retry-After and never reaches its handler.
 *
 * Also caps request bodies at [maxBodyBytes] (413) by Content-Length, except the local media
 * upload, which carries image bytes. Every JSON body in the API is far below the cap.
 */
fun Application.configureRequestLimits(
    rateLimitService: RateLimitService,
    trustedProxyHops: Int = 1,
    maxBodyBytes: Long = DEFAULT_MAX_BODY_BYTES,
    // Local dev: every QA script and seed run shares 127.0.0.1, so per-IP rules would trip them.
    perIpLimits: Boolean = true,
) {
    val logger = LoggerFactory.getLogger("RequestLimits")
    install(
        createApplicationPlugin("PynapliRequestLimits") {
            onCall { call ->
                val path = call.request.path()
                val declaredLength = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
                if (declaredLength != null && declaredLength > maxBodyBytes && !path.startsWith(LOCAL_MEDIA_UPLOAD_PREFIX)) {
                    call.respondLimit(HttpStatusCode.PayloadTooLarge, "PAYLOAD_TOO_LARGE", retryAfterSeconds = null)
                    return@onCall
                }
                val rule =
                    REQUEST_LIMIT_RULES
                        .firstOrNull { it.matches(call.request.httpMethod, path) }
                        ?.takeIf { perIpLimits || it.keyBy != LimitKey.CLIENT_IP }
                        ?: return@onCall
                val subject =
                    when (rule.keyBy) {
                        LimitKey.SESSION -> call.bearerTokenOrNull()?.let { "session:" + Sha256.hash(it) } ?: ("ip:" + call.clientIp(trustedProxyHops))
                        LimitKey.CLIENT_IP -> "ip:" + call.clientIp(trustedProxyHops)
                    }
                val decision =
                    runCatching { rateLimitService.checkSubject(subject, rule.action) }
                        .onFailure { error -> logger.warn("Request limit check failed for {}: {}", rule.action, error.message) }
                        // Redis trouble must not take the API down: fail open.
                        .getOrNull() ?: return@onCall
                if (!decision.allowed) {
                    call.respondLimit(HttpStatusCode.TooManyRequests, "RATE_LIMITED", retryAfterSeconds = decision.ttlSeconds.coerceAtLeast(1))
                }
            }
        },
    )
}

/**
 * The client's IP. Behind Cloud Run / a load balancer the platform appends the address it saw to
 * X-Forwarded-For, so with [trustedProxyHops] trusted proxies the client is that many entries from
 * the end; anything further left was written by the client and can be forged.
 */
internal fun ApplicationCall.clientIp(trustedProxyHops: Int): String {
    val forwarded =
        request.headers[HttpHeaders.XForwardedFor]
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
    if (trustedProxyHops > 0 && forwarded.size >= trustedProxyHops) return forwarded[forwarded.size - trustedProxyHops]
    return request.origin.remoteHost
}

private suspend fun ApplicationCall.respondLimit(
    status: HttpStatusCode,
    code: String,
    retryAfterSeconds: Long?,
) {
    retryAfterSeconds?.let { response.header(HttpHeaders.RetryAfter, it.toString()) }
    respond(status, ApiErrorDto(code = code, message = userSafeMessage(code), requestId = request.headers["X-Request-Id"] ?: "unknown"))
}

internal enum class LimitKey { SESSION, CLIENT_IP }

internal data class RequestLimitRule(
    val method: HttpMethod,
    val path: Regex,
    val action: String,
    val keyBy: LimitKey = LimitKey.SESSION,
) {
    fun matches(
        requestMethod: HttpMethod,
        requestPath: String,
    ): Boolean = requestMethod == method && path.matches(requestPath)
}

private const val ID = "[^/]+"
private const val LOCAL_MEDIA_UPLOAD_PREFIX = "/v1/media/uploads/local/"
const val DEFAULT_MAX_BODY_BYTES: Long = 1_048_576L

internal val REQUEST_LIMIT_RULES =
    listOf(
        // Before there is a session: per client IP, on top of the existing per-email/device limits,
        // so one address can't spray guesses across many accounts.
        RequestLimitRule(HttpMethod.Post, Regex("/v1/auth/email/login"), RateLimitedAction.AUTH_LOGIN_IP, LimitKey.CLIENT_IP),
        RequestLimitRule(HttpMethod.Post, Regex("/v1/auth/email/signup"), RateLimitedAction.AUTH_SIGNUP_IP, LimitKey.CLIENT_IP),
        RequestLimitRule(HttpMethod.Post, Regex("/v1/auth/password/reset-request"), RateLimitedAction.AUTH_PASSWORD_RESET_IP, LimitKey.CLIENT_IP),
        RequestLimitRule(HttpMethod.Post, Regex("/v1/auth/account-appeal"), RateLimitedAction.ACCOUNT_APPEAL_IP, LimitKey.CLIENT_IP),
        // Signed in: per session.
        RequestLimitRule(HttpMethod.Post, Regex("/v1/messages/threads/$ID/messages"), RateLimitedAction.MESSAGE_SEND),
        RequestLimitRule(HttpMethod.Post, Regex("/v1/messages/threads/$ID/media"), RateLimitedAction.MESSAGE_SEND),
        RequestLimitRule(HttpMethod.Post, Regex("/v1/messages/threads"), RateLimitedAction.THREAD_CREATE),
        RequestLimitRule(HttpMethod.Post, Regex("/v1/messages/threads/$ID/typing"), RateLimitedAction.TYPING),
        RequestLimitRule(HttpMethod.Post, Regex("/v1/profile-links/invitations"), RateLimitedAction.PROFILE_LINK_INVITE),
        RequestLimitRule(HttpMethod.Post, Regex("/v1/tonight/sessions"), RateLimitedAction.SESSION_CREATE),
        RequestLimitRule(HttpMethod.Post, Regex("/v1/psync/sessions"), RateLimitedAction.SESSION_CREATE),
        RequestLimitRule(HttpMethod.Post, Regex("/v1/blocks/$ID"), RateLimitedAction.BLOCK_OR_HIDE),
        RequestLimitRule(HttpMethod.Post, Regex("/v1/hidden-profiles/$ID"), RateLimitedAction.BLOCK_OR_HIDE),
        RequestLimitRule(HttpMethod.Post, Regex("/v1/location/current"), RateLimitedAction.LOCATION_UPDATE),
        RequestLimitRule(HttpMethod.Post, Regex("/v1/account/data-export"), RateLimitedAction.DATA_EXPORT),
        RequestLimitRule(HttpMethod.Post, Regex("/v1/appeals"), RateLimitedAction.APPEAL),
        RequestLimitRule(HttpMethod.Post, Regex("/v1/notifications/devices"), RateLimitedAction.PUSH_REGISTER),
    )
