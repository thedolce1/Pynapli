package com.pynapli.server.messaging

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

class MessageSafetyPolicy {
    fun evaluateFirstMessage(
        body: String,
        recentDuplicateCount: Int,
        bodyFingerprint: String = fingerprint(body)
    ): MessageSafetyDecision =
        when {
            containsExternalLink(body) -> MessageSafetyDecision.held(
                reason = MessageSafetyReason.LINK_FIRST_MESSAGE,
                bodyFingerprint = bodyFingerprint
            )
            recentDuplicateCount >= DUPLICATE_FIRST_MESSAGE_PREVIOUS_LIMIT -> MessageSafetyDecision.held(
                reason = MessageSafetyReason.DUPLICATE_FIRST_MESSAGE,
                bodyFingerprint = bodyFingerprint
            )
            else -> MessageSafetyDecision.visible(bodyFingerprint)
        }

    fun fingerprint(body: String): String {
        val normalized = body
            .trim()
            .lowercase()
            .replace(WHITESPACE, " ")
        return MessageDigest.getInstance("SHA-256")
            .digest(normalized.toByteArray(StandardCharsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun containsExternalLink(body: String): Boolean =
        EXTERNAL_LINK_PATTERN.containsMatchIn(body)

    companion object {
        const val DUPLICATE_FIRST_MESSAGE_PREVIOUS_LIMIT = 2
        const val DUPLICATE_LOOKBACK_SECONDS = 86_400L

        private val WHITESPACE = Regex("\\s+")
        private val EXTERNAL_LINK_PATTERN = Regex(
            pattern = """(?i)(https?://|www\.|(?:^|\s)[a-z0-9][a-z0-9.-]*\.(?:com|net|org|io|co|app|me|site|xyz|club|info)\b|(?:telegram|whatsapp|snapchat|cashapp|onlyfans)\b)"""
        )
    }
}

object MessageModerationStatus {
    const val VISIBLE = "visible"
    const val HELD = "held"
}

object MessageSafetyReason {
    const val DUPLICATE_FIRST_MESSAGE = "duplicate_first_message"
    const val LINK_FIRST_MESSAGE = "link_first_message"
}

data class MessageSafetyDecision(
    val moderationStatus: String,
    val safetyReason: String?,
    val bodyFingerprint: String
) {
    companion object {
        fun visible(bodyFingerprint: String): MessageSafetyDecision =
            MessageSafetyDecision(
                moderationStatus = MessageModerationStatus.VISIBLE,
                safetyReason = null,
                bodyFingerprint = bodyFingerprint
            )

        fun held(reason: String, bodyFingerprint: String): MessageSafetyDecision =
            MessageSafetyDecision(
                moderationStatus = MessageModerationStatus.HELD,
                safetyReason = reason,
                bodyFingerprint = bodyFingerprint
            )
    }
}
