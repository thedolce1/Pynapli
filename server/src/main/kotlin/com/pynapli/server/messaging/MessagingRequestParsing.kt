package com.pynapli.server.messaging

import com.pynapli.shared.network.dto.MESSAGE_IDEMPOTENCY_KEY_MAX_LENGTH
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Base64
import java.util.UUID

internal fun String.toMessagingUuidOrThrow(): UUID = runCatching { UUID.fromString(this) }.getOrElse { throw MessagingException("INVALID_ID") }

internal fun String?.toMessageLimitOrThrow(): Int {
    val value = this?.takeIf { it.isNotBlank() } ?: return MessagingServiceDefaults.MessagePageLimit
    return value.toIntOrNull()?.takeIf { it in 1..MessagingServiceDefaults.MaxMessagePageLimit }
        ?: throw MessagingException("INVALID_MESSAGE_PAGE_LIMIT")
}

internal fun String.toMessageCursorOrThrow(): MessagePageCursor =
    runCatching {
        val decoded = String(Base64.getUrlDecoder().decode(this), StandardCharsets.UTF_8)
        val separator = decoded.indexOf('|')
        if (separator <= 0 || separator == decoded.lastIndex) throw IllegalArgumentException("Invalid cursor")
        MessagePageCursor(
            createdAt = Instant.parse(decoded.substring(0, separator)),
            messageId = UUID.fromString(decoded.substring(separator + 1)),
        )
    }.getOrElse { throw MessagingException("INVALID_MESSAGE_CURSOR") }

internal fun String?.toClientIdempotencyKeyOrThrow(): String? {
    val key = this?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (key.length > MESSAGE_IDEMPOTENCY_KEY_MAX_LENGTH) throw MessagingException("INVALID_IDEMPOTENCY_KEY")
    return key
}
