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
import io.ktor.server.application.hooks.ReceiveRequestBytes
import io.ktor.server.application.install
import io.ktor.server.plugins.PayloadTooLargeException
import io.ktor.server.plugins.origin
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.writer
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Request-level abuse limits, on top of the per-feature limits services apply themselves.
 *
 * Counters live in Redis (via [RateLimitService]), so every replica shares them. Each request is
 * checked against:
 * - global ceilings (abuse-level, per minute): every request per client IP, requests with no bearer
 *   token per client IP, and every signed-in request per user;
 * - every matching rule in [REQUEST_LIMIT_RULES]: signed-in rules are keyed by the user the bearer
 *   token belongs to ([resolveUserId]), so signing in again doesn't reset a budget; sign-in-type
 *   rules that run before there is a session are keyed by client IP.
 * Over any limit the request is answered 429 RATE_LIMITED with Retry-After and never reaches its
 * handler. Redis trouble fails open: abuse limits must not take the API down.
 *
 * Also caps request bodies at [maxBodyBytes] (413), both by Content-Length and on the bytes actually
 * read, so a chunked or header-less body can't slip past. The local media upload carries image
 * bytes and is exempt. Every JSON body in the API is far below the cap.
 */
fun Application.configureRequestLimits(
    rateLimitService: RateLimitService,
    trustedProxyHops: Int = 1,
    maxBodyBytes: Long = DEFAULT_MAX_BODY_BYTES,
    // Local dev: every QA script and seed run shares 127.0.0.1, so per-IP rules would trip them.
    perIpLimits: Boolean = true,
    // The user behind a bearer token, or null when it isn't a live session. Null resolver (tests,
    // tools): rules fall back to a hash of the token.
    resolveUserId: (suspend (String) -> UUID?)? = null,
    // The global ceilings are abuse-level backstops, off with the per-IP rules in local dev.
    globalCeilings: Boolean = perIpLimits,
) {
    val logger = LoggerFactory.getLogger("RequestLimits")
    install(
        createApplicationPlugin("PynapliRequestLimits") {
            on(ReceiveRequestBytes) { call, body ->
                if (call.request.path().startsWith(LOCAL_MEDIA_UPLOAD_PREFIX)) body else call.cappedAt(body, maxBodyBytes)
            }
            onCall { call ->
                val path = call.request.path()
                val declaredLength = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
                if (declaredLength != null && declaredLength > maxBodyBytes && !path.startsWith(LOCAL_MEDIA_UPLOAD_PREFIX)) {
                    call.respondLimit(HttpStatusCode.PayloadTooLarge, "PAYLOAD_TOO_LARGE", retryAfterSeconds = null)
                    return@onCall
                }
                val method = call.request.httpMethod
                val rules =
                    REQUEST_LIMIT_RULES.filter { it.matches(method, path) && (perIpLimits || it.keyBy != LimitKey.CLIENT_IP) }
                val ceilingsApply = globalCeilings && GLOBAL_CEILING_EXEMPT_PREFIXES.none { path.startsWith(it) }
                if (rules.isEmpty() && !ceilingsApply) return@onCall

                val ip = "ip:" + call.clientIp(trustedProxyHops)
                val token = call.bearerTokenOrNull()
                val caller =
                    when {
                        token == null -> ip
                        resolveUserId == null -> "session:" + Sha256.hash(token)
                        // A token that isn't a live session gets the address's budget, not a fresh one.
                        else -> runCatching { resolveUserId(token) }.getOrNull()?.let { "user:$it" } ?: ip
                    }

                val checks =
                    buildList {
                        if (ceilingsApply) {
                            add(ip to RateLimitedAction.GLOBAL_IP)
                            if (token == null) add(ip to RateLimitedAction.GLOBAL_ANONYMOUS_IP) else add(caller to RateLimitedAction.GLOBAL_USER)
                        }
                        rules.forEach { rule ->
                            val subject =
                                when (rule.keyBy) {
                                    LimitKey.USER -> caller
                                    LimitKey.CLIENT_IP -> ip
                                }
                            add((rule.subjectSuffix(path)?.let { "$subject:$it" } ?: subject) to rule.action)
                        }
                    }
                for ((subject, action) in checks) {
                    val decision =
                        runCatching { rateLimitService.checkSubject(subject, action) }
                            .onFailure { error -> logger.warn("Request limit check failed for {}: {}", action, error.message) }
                            .getOrNull() ?: return@onCall
                    if (!decision.allowed) {
                        call.respondLimit(HttpStatusCode.TooManyRequests, "RATE_LIMITED", retryAfterSeconds = decision.ttlSeconds.coerceAtLeast(1))
                        return@onCall
                    }
                }
            }
        },
    )
}

/** Passes [body] through, failing the read with [PayloadTooLargeException] once it exceeds [limit] bytes. */
private fun ApplicationCall.cappedAt(
    body: ByteReadChannel,
    limit: Long,
): ByteReadChannel =
    writer {
        val buffer = ByteArray(BODY_COPY_BUFFER_BYTES)
        var total = 0L
        while (true) {
            val read = body.readAvailable(buffer, 0, buffer.size)
            if (read == -1) break
            total += read
            if (total > limit) throw PayloadTooLargeException(limit)
            channel.writeFully(buffer, 0, read)
        }
    }.channel

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

internal enum class LimitKey { USER, CLIENT_IP }

internal data class RequestLimitRule(
    val method: HttpMethod,
    val path: Regex,
    val action: String,
    val keyBy: LimitKey = LimitKey.USER,
    // Narrows the budget to one object on the path (the first capture group), e.g. one conversation.
    val perPathObject: Boolean = false,
) {
    fun matches(
        requestMethod: HttpMethod,
        requestPath: String,
    ): Boolean = requestMethod == method && path.matches(requestPath)

    fun subjectSuffix(requestPath: String): String? =
        if (perPathObject) path.matchEntire(requestPath)?.groupValues?.getOrNull(1)?.let { "obj:$it" } else null
}

private const val ID = "[^/]+"
private const val LOCAL_MEDIA_UPLOAD_PREFIX = "/v1/media/uploads/local/"
private const val BODY_COPY_BUFFER_BYTES = 8_192
const val DEFAULT_MAX_BODY_BYTES: Long = 1_048_576L

// Infrastructure probes, the metrics scrape, and server-to-server callbacks (authenticated by their
// own tokens/signatures) never count toward the global ceilings.
private val GLOBAL_CEILING_EXEMPT_PREFIXES =
    listOf("/health", "/metrics", "/v1/webhooks", "/v1/verification/callback", LOCAL_MEDIA_UPLOAD_PREFIX)

internal val REQUEST_LIMIT_RULES =
    listOf(
        // Before there is a session: per client IP, on top of the existing per-email/device limits,
        // so one address can't spray guesses across many accounts.
        RequestLimitRule(HttpMethod.Post, Regex("/v1/auth/email/login"), RateLimitedAction.AUTH_LOGIN_IP, LimitKey.CLIENT_IP),
        RequestLimitRule(HttpMethod.Post, Regex("/v1/auth/email/signup"), RateLimitedAction.AUTH_SIGNUP_IP, LimitKey.CLIENT_IP),
        RequestLimitRule(HttpMethod.Post, Regex("/v1/auth/password/reset-request"), RateLimitedAction.AUTH_PASSWORD_RESET_IP, LimitKey.CLIENT_IP),
        RequestLimitRule(HttpMethod.Post, Regex("/v1/auth/account-appeal"), RateLimitedAction.ACCOUNT_APPEAL_IP, LimitKey.CLIENT_IP),
        // Signed in: per user.
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
        // Reads where walking ids would enumerate other people's data.
        RequestLimitRule(HttpMethod.Get, Regex("/v1/media/users/$ID/albums/private"), RateLimitedAction.PRIVATE_ALBUM_FETCH),
    )
