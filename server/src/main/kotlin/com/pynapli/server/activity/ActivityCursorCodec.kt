package com.pynapli.server.activity

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class ActivityCursorCodec(
    secret: String,
    private val json: Json =
        Json {
            encodeDefaults = true
            ignoreUnknownKeys = false
        },
) {
    private val secretBytes =
        secret
            .also { value ->
                require(value.isNotBlank()) { "Activity cursor secret must not be blank." }
            }.toByteArray(StandardCharsets.UTF_8)

    fun encode(
        scopeHash: String,
        anchor: ActivityCursorAnchor,
    ): String =
        encodePayload(
            ActivityCursorPayload(
                surface = SURFACE_ACTIVITY,
                scopeHash = scopeHash,
                occurredAt = anchor.occurredAt.toString(),
                eventId = anchor.eventId.toString(),
                seenCount = anchor.seenCount,
            ),
        )

    fun decode(
        token: String,
        expectedScopeHash: String,
    ): ActivityCursorAnchor {
        val payload = decodePayload(token)
        if (payload.surface != SURFACE_ACTIVITY || payload.version != VERSION || payload.scopeHash != expectedScopeHash) {
            throw ActivityCursorException()
        }

        return ActivityCursorAnchor(
            occurredAt =
                runCatching {
                    Instant.parse(payload.occurredAt ?: throw ActivityCursorException())
                }.getOrElse { throw ActivityCursorException() },
            eventId =
                runCatching {
                    UUID.fromString(payload.eventId ?: throw ActivityCursorException())
                }.getOrElse { throw ActivityCursorException() },
            seenCount = payload.seenCount?.coerceAtLeast(0) ?: 0,
        )
    }

    private fun encodePayload(payload: ActivityCursorPayload): String {
        val payloadJson = json.encodeToString(ActivityCursorPayload.serializer(), payload)
        val payloadSegment = base64UrlEncode(payloadJson.toByteArray(StandardCharsets.UTF_8))
        val signedValue = "$TOKEN_VERSION.$payloadSegment"
        val signature = base64UrlEncode(hmacSha256(signedValue))
        return "$signedValue.$signature"
    }

    private fun decodePayload(token: String): ActivityCursorPayload {
        val parts = token.split(".")
        if (parts.size != 3 || parts[0] != TOKEN_VERSION) throw ActivityCursorException()

        val signedValue = "${parts[0]}.${parts[1]}"
        val expectedSignature = hmacSha256(signedValue)
        val suppliedSignature = runCatching { base64UrlDecode(parts[2]) }.getOrElse { throw ActivityCursorException() }
        if (!MessageDigest.isEqual(expectedSignature, suppliedSignature)) throw ActivityCursorException()

        val payloadJson =
            runCatching {
                String(base64UrlDecode(parts[1]), StandardCharsets.UTF_8)
            }.getOrElse { throw ActivityCursorException() }

        return runCatching {
            json.decodeFromString(ActivityCursorPayload.serializer(), payloadJson)
        }.getOrElse { throw ActivityCursorException() }
    }

    private fun hmacSha256(value: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secretBytes, "HmacSHA256"))
        return mac.doFinal(value.toByteArray(StandardCharsets.UTF_8))
    }

    companion object {
        private const val VERSION = 1
        private const val TOKEN_VERSION = "v1"
        private const val SURFACE_ACTIVITY = "activity"

        fun scopeHash(
            viewerUserId: UUID,
            tab: ActivityFeedTab,
        ): String {
            val normalized = "activity:v1|viewer=$viewerUserId|tab=${tab.value}"
            return base64UrlEncode(MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray(StandardCharsets.UTF_8)))
        }

        private fun base64UrlEncode(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

        private fun base64UrlDecode(value: String): ByteArray = Base64.getUrlDecoder().decode(value)
    }
}

class ActivityCursorException : RuntimeException("INVALID_ACTIVITY_CURSOR")

enum class ActivityFeedTab(
    val value: String,
    val eventTypes: Set<String>,
) {
    ALL("all", emptySet()),
    VIEWS("views", setOf("profile_view")),

    // Flirts is the unified inbound-interest surface (Grindr taps + swipe-likes + Pineapple likes +
    // Intro Notes) and carries the premium reveal gating — "Likes" folded into it.
    FLIRTS("flirts", setOf("flirt", "swipe_like", "pineapple_like", "intro_note")),
    MATCHES("matches", setOf("match")),

    // "Requests" folds album-access requests and partner/psync/tonight invites into one
    // action-oriented tab (mobile Activity surface shows Flirts/Views/Matches/Requests).
    REQUESTS(
        "requests",
        setOf(
            "album_request",
            "partner_chat_invite",
            "psync_invite",
            "psync_started",
            "psync_recap",
            "psync_expired",
            "playing_tonight",
        ),
    ),
    ;

    companion object {
        fun from(value: String?): ActivityFeedTab {
            val normalized = value?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: ALL.value
            return entries.firstOrNull { it.value == normalized } ?: throw ActivityException("INVALID_ACTIVITY_TAB")
        }

        fun fromEventType(eventType: String): ActivityFeedTab = entries.firstOrNull { tab -> eventType in tab.eventTypes } ?: ALL
    }
}

@Serializable
private data class ActivityCursorPayload(
    val version: Int = 1,
    val surface: String,
    val scopeHash: String,
    val occurredAt: String? = null,
    val eventId: String? = null,
    val seenCount: Int? = null,
)
