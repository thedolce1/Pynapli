package com.pynapli.server.moderation

import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal data class AdminActor(
    val actorId: String,
)

internal fun ApplicationCall.requireAdminActor(expectedToken: String): AdminActor {
    val suppliedToken =
        request.headers["X-Admin-Token"]
            ?: request.headers[HttpHeaders.Authorization]
                ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
                ?.substringAfter("Bearer ")
                ?.trim()
            ?: throw ModerationException("ADMIN_UNAUTHENTICATED")

    if (!constantTimeEquals(expectedToken, suppliedToken)) {
        throw ModerationException("ADMIN_FORBIDDEN")
    }

    val actorId =
        request.headers["X-Admin-Id"]
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: throw ModerationException("INVALID_ADMIN_ACTOR")
    return AdminActor(actorId = actorId)
}

internal fun ApplicationCall.requiredAdminReason(): String =
    request.headers["X-Admin-Reason"]
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: throw ModerationException("MODERATION_REASON_REQUIRED")

private fun constantTimeEquals(
    expected: String,
    actual: String,
): Boolean =
    MessageDigest.isEqual(
        expected.toByteArray(StandardCharsets.UTF_8),
        actual.toByteArray(StandardCharsets.UTF_8),
    )
