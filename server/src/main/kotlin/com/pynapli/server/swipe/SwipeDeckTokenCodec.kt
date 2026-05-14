package com.pynapli.server.swipe

import com.pynapli.server.discovery.DiscoveryFilters
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class SwipeDeckTokenCodec(
    secret: String = DEFAULT_SECRET,
    private val json: Json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
    }
) {
    private val secretBytes = secret.also { value ->
        require(value.isNotBlank()) { "Swipe deck token secret must not be blank." }
    }.toByteArray(StandardCharsets.UTF_8)

    fun issueDeckSession(
        viewerUserId: UUID,
        scopeHash: String,
        now: Instant,
        ttl: Duration = DEFAULT_SESSION_TTL,
        nonce: UUID = UUID.randomUUID()
    ): IssuedSwipeDeckSession {
        val expiresAt = now.plus(ttl)
        val deckSessionId = encode(
            SwipeDeckTokenPayload(
                kind = KIND_DECK_SESSION,
                viewerUserId = viewerUserId.toString(),
                scopeHash = scopeHash,
                generatedAtEpochSeconds = now.epochSecond,
                expiresAtEpochSeconds = expiresAt.epochSecond,
                nonce = nonce.toString()
            ),
            invalidCode = INVALID_DECK_SESSION
        )
        return IssuedSwipeDeckSession(
            deckSessionId = deckSessionId,
            generatedAt = now,
            expiresAt = expiresAt
        )
    }

    fun issueCardToken(
        deckSessionId: String,
        viewerUserId: UUID,
        targetUserId: UUID,
        scopeHash: String,
        generatedAt: Instant,
        expiresAt: Instant,
        position: Int
    ): String =
        encode(
            SwipeDeckTokenPayload(
                kind = KIND_CARD,
                viewerUserId = viewerUserId.toString(),
                targetUserId = targetUserId.toString(),
                scopeHash = scopeHash,
                deckSessionHash = deckSessionHash(deckSessionId),
                generatedAtEpochSeconds = generatedAt.epochSecond,
                expiresAtEpochSeconds = expiresAt.epochSecond,
                position = position
            ),
            invalidCode = INVALID_CARD_TOKEN
        )

    fun validateAction(
        deckSessionId: String,
        cardToken: String,
        viewerUserId: UUID,
        targetUserId: UUID,
        now: Instant
    ) {
        val session = decode(deckSessionId, invalidCode = INVALID_DECK_SESSION)
        if (
            session.version != VERSION ||
            session.kind != KIND_DECK_SESSION ||
            session.viewerUserId != viewerUserId.toString() ||
            session.scopeHash.isBlank()
        ) {
            throw SwipeDeckTokenException(INVALID_DECK_SESSION)
        }
        if (now.epochSecond >= session.expiresAtEpochSeconds) {
            throw SwipeDeckTokenException(STALE_DECK_SESSION)
        }

        val card = decode(cardToken, invalidCode = INVALID_CARD_TOKEN)
        if (
            card.version != VERSION ||
            card.kind != KIND_CARD ||
            card.viewerUserId != viewerUserId.toString() ||
            card.targetUserId != targetUserId.toString() ||
            card.scopeHash != session.scopeHash ||
            card.deckSessionHash != deckSessionHash(deckSessionId)
        ) {
            throw SwipeDeckTokenException(INVALID_CARD_TOKEN)
        }
        if (now.epochSecond >= card.expiresAtEpochSeconds) {
            throw SwipeDeckTokenException(STALE_DECK_SESSION)
        }
    }

    private fun encode(payload: SwipeDeckTokenPayload, invalidCode: String): String {
        val payloadJson = runCatching {
            json.encodeToString(SwipeDeckTokenPayload.serializer(), payload)
        }.getOrElse { throw SwipeDeckTokenException(invalidCode) }
        val payloadSegment = base64UrlEncode(payloadJson.toByteArray(StandardCharsets.UTF_8))
        val signedValue = "$TOKEN_VERSION.$payloadSegment"
        val signature = base64UrlEncode(hmacSha256(signedValue))
        return "$signedValue.$signature"
    }

    private fun decode(token: String, invalidCode: String): SwipeDeckTokenPayload {
        val parts = token.split(".")
        if (parts.size != 3 || parts[0] != TOKEN_VERSION) throw SwipeDeckTokenException(invalidCode)

        val signedValue = "${parts[0]}.${parts[1]}"
        val expectedSignature = hmacSha256(signedValue)
        val suppliedSignature = runCatching { base64UrlDecode(parts[2]) }
            .getOrElse { throw SwipeDeckTokenException(invalidCode) }
        if (!MessageDigest.isEqual(expectedSignature, suppliedSignature)) {
            throw SwipeDeckTokenException(invalidCode)
        }

        val payloadJson = runCatching {
            String(base64UrlDecode(parts[1]), StandardCharsets.UTF_8)
        }.getOrElse { throw SwipeDeckTokenException(invalidCode) }

        return runCatching {
            json.decodeFromString(SwipeDeckTokenPayload.serializer(), payloadJson)
        }.getOrElse { throw SwipeDeckTokenException(invalidCode) }
    }

    private fun hmacSha256(value: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secretBytes, "HmacSHA256"))
        return mac.doFinal(value.toByteArray(StandardCharsets.UTF_8))
    }

    companion object {
        const val DEFAULT_SECRET = "pynapli-local-dev-swipe-deck-token-secret"
        const val INVALID_DECK_SESSION = "INVALID_SWIPE_DECK_SESSION"
        const val STALE_DECK_SESSION = "STALE_SWIPE_DECK_SESSION"
        const val INVALID_CARD_TOKEN = "INVALID_SWIPE_CARD_TOKEN"

        private const val VERSION = 1
        private const val TOKEN_VERSION = "v1"
        private const val KIND_DECK_SESSION = "deck_session"
        private const val KIND_CARD = "card"
        private val DEFAULT_SESSION_TTL: Duration = Duration.ofMinutes(30)

        fun deckScopeHash(
            viewerUserId: UUID,
            radiusMeters: Int,
            limit: Int,
            filters: DiscoveryFilters
        ): String {
            val normalized = buildString {
                append("swipe_deck:v1")
                append("|viewer=").append(viewerUserId)
                append("|radius=").append(radiusMeters)
                append("|limit=").append(limit)
                append("|minAge=").append(filters.minimumAge ?: "")
                append("|maxAge=").append(filters.maximumAge ?: "")
                append("|linkedOnly=").append(filters.linkedOnly ?: "")
                append("|verifiedOnly=").append(filters.verifiedOnly)
                append("|facePhotoVerifiedOnly=").append(filters.facePhotoVerifiedOnly)
                append("|ageVerifiedOnly=").append(filters.ageVerifiedOnly)
                append("|relationshipContexts=")
                append(filters.relationshipContexts.sorted().joinToString(","))
                append("|discoveryStyles=")
                append(filters.discoveryStyles.sorted().joinToString(","))
                append("|lifestyleTags=")
                append(filters.lifestyleTags.sorted().joinToString(","))
                append("|logisticsTags=")
                append(filters.logisticsTags.sorted().joinToString(","))
                append("|tonightAvailableOnly=").append(filters.tonightAvailableOnly)
                append("|intents=")
                append(filters.intents.sorted().joinToString(","))
            }
            return sha256Base64Url(normalized)
        }

        private fun deckSessionHash(deckSessionId: String): String = sha256Base64Url(deckSessionId)

        private fun sha256Base64Url(value: String): String =
            base64UrlEncode(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8)))

        private fun base64UrlEncode(bytes: ByteArray): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

        private fun base64UrlDecode(value: String): ByteArray =
            Base64.getUrlDecoder().decode(value)
    }
}

data class IssuedSwipeDeckSession(
    val deckSessionId: String,
    val generatedAt: Instant,
    val expiresAt: Instant
)

class SwipeDeckTokenException(val code: String) : RuntimeException(code)

@Serializable
private data class SwipeDeckTokenPayload(
    val version: Int = 1,
    val kind: String,
    val viewerUserId: String,
    val scopeHash: String,
    val targetUserId: String? = null,
    val deckSessionHash: String? = null,
    val generatedAtEpochSeconds: Long,
    val expiresAtEpochSeconds: Long,
    val nonce: String? = null,
    val position: Int? = null
)
