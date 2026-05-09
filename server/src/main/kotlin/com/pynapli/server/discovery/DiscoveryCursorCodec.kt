package com.pynapli.server.discovery

import com.pynapli.server.grid.GridCursorAnchor
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class DiscoveryCursorCodec(
    secret: String,
    private val json: Json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
    }
) {
    private val secretBytes = secret.also { value ->
        require(value.isNotBlank()) { "Discovery cursor secret must not be blank." }
    }.toByteArray(StandardCharsets.UTF_8)

    fun encodeGridCursor(scopeHash: String, anchor: GridCursorAnchor): String =
        encode(
            DiscoveryCursorPayload(
                surface = SURFACE_GRID,
                scopeHash = scopeHash,
                boostBucket = anchor.boostBucket,
                distanceBucketMeters = anchor.distanceBucketMeters,
                userId = anchor.userId.toString()
            )
        )

    fun decodeGridCursor(token: String, expectedScopeHash: String): GridCursorAnchor {
        val payload = decode(token)
        if (payload.surface != SURFACE_GRID || payload.version != VERSION || payload.scopeHash != expectedScopeHash) {
            throw DiscoveryCursorException()
        }

        return GridCursorAnchor(
            boostBucket = payload.boostBucket ?: throw DiscoveryCursorException(),
            distanceBucketMeters = payload.distanceBucketMeters ?: throw DiscoveryCursorException(),
            userId = runCatching {
                UUID.fromString(payload.userId ?: throw DiscoveryCursorException())
            }.getOrElse { throw DiscoveryCursorException() }
        )
    }

    private fun encode(payload: DiscoveryCursorPayload): String {
        val payloadJson = json.encodeToString(DiscoveryCursorPayload.serializer(), payload)
        val payloadSegment = base64UrlEncode(payloadJson.toByteArray(StandardCharsets.UTF_8))
        val signedValue = "$TOKEN_VERSION.$payloadSegment"
        val signature = base64UrlEncode(hmacSha256(signedValue))
        return "$signedValue.$signature"
    }

    private fun decode(token: String): DiscoveryCursorPayload {
        val parts = token.split(".")
        if (parts.size != 3 || parts[0] != TOKEN_VERSION) throw DiscoveryCursorException()

        val signedValue = "${parts[0]}.${parts[1]}"
        val expectedSignature = hmacSha256(signedValue)
        val suppliedSignature = runCatching { base64UrlDecode(parts[2]) }.getOrElse { throw DiscoveryCursorException() }
        if (!MessageDigest.isEqual(expectedSignature, suppliedSignature)) throw DiscoveryCursorException()

        val payloadJson = runCatching {
            String(base64UrlDecode(parts[1]), StandardCharsets.UTF_8)
        }.getOrElse { throw DiscoveryCursorException() }

        return runCatching {
            json.decodeFromString(DiscoveryCursorPayload.serializer(), payloadJson)
        }.getOrElse { throw DiscoveryCursorException() }
    }

    private fun hmacSha256(value: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secretBytes, "HmacSHA256"))
        return mac.doFinal(value.toByteArray(StandardCharsets.UTF_8))
    }

    companion object {
        private const val VERSION = 1
        private const val TOKEN_VERSION = "v1"
        private const val SURFACE_GRID = "grid"

        fun gridScopeHash(
            viewerUserId: UUID,
            radiusMeters: Int,
            limit: Int,
            filters: DiscoveryFilters,
            onlineOnly: Boolean
        ): String {
            val normalized = buildString {
                append("grid:v1")
                append("|viewer=").append(viewerUserId)
                append("|radius=").append(radiusMeters)
                append("|limit=").append(limit)
                append("|minAge=").append(filters.minimumAge ?: "")
                append("|maxAge=").append(filters.maximumAge ?: "")
                append("|linkedOnly=").append(filters.linkedOnly ?: "")
                append("|onlineOnly=").append(onlineOnly)
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

        private fun sha256Base64Url(value: String): String =
            base64UrlEncode(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8)))

        private fun base64UrlEncode(bytes: ByteArray): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

        private fun base64UrlDecode(value: String): ByteArray =
            Base64.getUrlDecoder().decode(value)
    }
}

class DiscoveryCursorException : RuntimeException("INVALID_DISCOVERY_CURSOR")

@Serializable
private data class DiscoveryCursorPayload(
    val version: Int = 1,
    val surface: String,
    val scopeHash: String,
    val boostBucket: Int? = null,
    val distanceBucketMeters: Int? = null,
    val userId: String? = null
)
