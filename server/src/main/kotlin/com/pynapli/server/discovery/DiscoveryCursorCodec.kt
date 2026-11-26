package com.pynapli.server.discovery

import com.pynapli.server.grid.GridCursorAnchor
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class DiscoveryCursorCodec(
    secret: String,
    private val json: Json =
        Json {
            encodeDefaults = true
            ignoreUnknownKeys = false
        },
) {
    /**
     * AES-256 key derived from the existing cursor secret (no new config): HMAC-SHA256 keyed by the
     * secret over a fixed purpose label, so the encryption key is independent of any other use of
     * the same secret.
     */
    private val encryptionKey: SecretKeySpec =
        secret
            .also { value ->
                require(value.isNotBlank()) { "Discovery cursor secret must not be blank." }
            }.toByteArray(StandardCharsets.UTF_8)
            .let { secretBytes ->
                val mac = Mac.getInstance("HmacSHA256")
                mac.init(SecretKeySpec(secretBytes, "HmacSHA256"))
                SecretKeySpec(mac.doFinal(KEY_DERIVATION_LABEL.toByteArray(StandardCharsets.UTF_8)), "AES")
            }

    private val secureRandom = SecureRandom()

    fun encodeGridCursor(
        scopeHash: String,
        anchor: GridCursorAnchor,
    ): String =
        encode(
            DiscoveryCursorPayload(
                surface = SURFACE_GRID,
                scopeHash = scopeHash,
                boostBucket = anchor.boostBucket,
                orderBucket = anchor.orderBucket,
                orderTiebreak = anchor.orderTiebreak,
                userId = anchor.userId.toString(),
                seenCount = anchor.seenCount,
            ),
        )

    fun decodeGridCursor(
        token: String,
        expectedScopeHash: String,
    ): GridCursorAnchor {
        val payload = decode(token)
        if (payload.surface != SURFACE_GRID || payload.version != VERSION || payload.scopeHash != expectedScopeHash) {
            throw DiscoveryCursorException()
        }

        return GridCursorAnchor(
            boostBucket = payload.boostBucket ?: throw DiscoveryCursorException(),
            orderBucket = payload.orderBucket ?: throw DiscoveryCursorException(),
            userId =
                runCatching {
                    UUID.fromString(payload.userId ?: throw DiscoveryCursorException())
                }.getOrElse { throw DiscoveryCursorException() },
            seenCount = payload.seenCount ?: 0,
            orderTiebreak = payload.orderTiebreak ?: throw DiscoveryCursorException(),
        )
    }

    /** Favorites list position, sealed the same way as a Grid cursor but bound to its own surface. */
    fun encodeFavoritesCursor(
        viewerUserId: UUID,
        anchor: GridCursorAnchor,
    ): String =
        encode(
            DiscoveryCursorPayload(
                surface = SURFACE_FAVORITES,
                scopeHash = favoritesScopeHash(viewerUserId),
                boostBucket = anchor.boostBucket,
                orderBucket = anchor.orderBucket,
                orderTiebreak = anchor.orderTiebreak,
                userId = anchor.userId.toString(),
            ),
        )

    fun decodeFavoritesCursor(
        token: String,
        viewerUserId: UUID,
    ): GridCursorAnchor {
        val payload = decode(token)
        if (payload.surface != SURFACE_FAVORITES || payload.version != VERSION || payload.scopeHash != favoritesScopeHash(viewerUserId)) {
            throw DiscoveryCursorException()
        }
        return GridCursorAnchor(
            boostBucket = payload.boostBucket ?: throw DiscoveryCursorException(),
            orderBucket = payload.orderBucket ?: throw DiscoveryCursorException(),
            userId =
                runCatching {
                    UUID.fromString(payload.userId ?: throw DiscoveryCursorException())
                }.getOrElse { throw DiscoveryCursorException() },
            orderTiebreak = payload.orderTiebreak ?: throw DiscoveryCursorException(),
        )
    }

    /**
     * Sealed token: `g2.<base64url(nonce || AES-256-GCM(payload json))>`. The payload is encrypted, not
     * just signed (L-07): a client reading its own cursor learns nothing about the ranking anchor (an
     * old v1 cursor exposed a metre-exact distance bucket in readable base64 JSON). The GCM tag
     * authenticates the version prefix as associated data, so tampering, a foreign secret or an old
     * token all fail decryption and surface as [DiscoveryCursorException].
     */
    private fun encode(payload: DiscoveryCursorPayload): String {
        val payloadJson = json.encodeToString(DiscoveryCursorPayload.serializer(), payload)
        val nonce = ByteArray(GCM_NONCE_BYTES).also(secureRandom::nextBytes)
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey, GCMParameterSpec(GCM_TAG_BITS, nonce))
        cipher.updateAAD(TOKEN_VERSION_BYTES)
        val sealed = cipher.doFinal(payloadJson.toByteArray(StandardCharsets.UTF_8))
        return "$TOKEN_VERSION.${base64UrlEncode(nonce + sealed)}"
    }

    private fun decode(token: String): DiscoveryCursorPayload {
        val parts = token.split(".")
        if (parts.size != 2 || parts[0] != TOKEN_VERSION) throw DiscoveryCursorException()
        val box = runCatching { base64UrlDecode(parts[1]) }.getOrElse { throw DiscoveryCursorException() }
        if (box.size < GCM_NONCE_BYTES + GCM_TAG_BITS / 8) throw DiscoveryCursorException()

        val payloadJson =
            runCatching {
                val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
                cipher.init(
                    Cipher.DECRYPT_MODE,
                    encryptionKey,
                    GCMParameterSpec(GCM_TAG_BITS, box, 0, GCM_NONCE_BYTES),
                )
                cipher.updateAAD(TOKEN_VERSION_BYTES)
                String(cipher.doFinal(box, GCM_NONCE_BYTES, box.size - GCM_NONCE_BYTES), StandardCharsets.UTF_8)
            }.getOrElse { throw DiscoveryCursorException() }

        return runCatching {
            json.decodeFromString(DiscoveryCursorPayload.serializer(), payloadJson)
        }.getOrElse { throw DiscoveryCursorException() }
    }

    companion object {
        private const val VERSION = 2
        private const val TOKEN_VERSION = "g2"
        private val TOKEN_VERSION_BYTES = TOKEN_VERSION.toByteArray(StandardCharsets.UTF_8)
        private const val KEY_DERIVATION_LABEL = "pynapli/discovery-cursor/aes-gcm/v2"
        private const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_NONCE_BYTES = 12
        private const val GCM_TAG_BITS = 128
        private const val SURFACE_GRID = "grid"
        private const val SURFACE_FAVORITES = "favorites"

        private fun favoritesScopeHash(viewerUserId: UUID): String = sha256Base64Url("favorites:v1|viewer=$viewerUserId")

        fun gridScopeHash(
            viewerUserId: UUID,
            radiusMeters: Int,
            limit: Int,
            filters: DiscoveryFilters,
            onlineOnly: Boolean,
            // Set on a sparse-area widened scope (GridService): [radiusMeters] is then the widened
            // radius and this is the radius the viewer asked for, so a widened cursor only ever
            // continues the exact request that produced it. Appended only when set.
            widenedFromRadiusMeters: Int? = null,
            // Section 3: the request opted in to locked cards. The request flag, never the tier, so an
            // entitlement change mid-scroll keeps the cursor valid. Appended only when set.
            acceptLockedCards: Boolean = false,
            // "Visiting soon": a different list with its own keyset order (arrival day first), so its
            // cursors must never continue Nearby or the reverse. Appended only when set.
            visitingSoonOnly: Boolean = false,
        ): String {
            val normalized =
                buildString {
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
                    append("|boundaryItems=")
                    append(filters.boundaryItems.sorted().joinToString(","))
                    append("|logisticsTags=")
                    append(filters.logisticsTags.sorted().joinToString(","))
                    append("|tonightAvailableOnly=").append(filters.tonightAvailableOnly)
                    append("|activeTodayOnly=").append(filters.activeTodayOnly)
                    // Fresh replaced "Recently joined" in the same slot; the label is kept so cursors
                    // issued before the change keep their hash.
                    append("|recentlyJoinedOnly=").append(filters.freshOnly)
                    append("|travelingHereOnly=").append(filters.travelingHereOnly)
                    append("|withPhotosOnly=").append(filters.withPhotosOnly)
                    append("|hasPrivateAlbumOnly=").append(filters.hasPrivateAlbumOnly)
                    append("|intents=")
                    append(filters.intents.sorted().joinToString(","))
                    // withPhotosOnly / genders / orientations were previously omitted here: a cursor
                    // is only valid within its full filter scope, so every hard filter must be hashed
                    // or a cursor could carry across a filter change and misalign pagination.
                    append("|genders=")
                    append(filters.genders.sorted().joinToString(","))
                    append("|orientations=")
                    append(filters.orientations.sorted().joinToString(","))
                    // Appended only when set, so cursors issued without it keep their hash.
                    if (filters.verifiedCouplesOnly) append("|verifiedCouplesOnly=true")
                    widenedFromRadiusMeters?.let { append("|widenedFrom=").append(it) }
                    if (acceptLockedCards) append("|lockedCards=true")
                    if (visitingSoonOnly) append("|visitingSoon=true")
                }
            return sha256Base64Url(normalized)
        }

        private fun sha256Base64Url(value: String): String = base64UrlEncode(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8)))

        private fun base64UrlEncode(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

        private fun base64UrlDecode(value: String): ByteArray {
            val decoded = Base64.getUrlDecoder().decode(value)
            if (base64UrlEncode(decoded) != value) {
                throw IllegalArgumentException("Non-canonical base64url cursor segment.")
            }
            return decoded
        }
    }
}

class DiscoveryCursorException : RuntimeException("INVALID_DISCOVERY_CURSOR")

@Serializable
private data class DiscoveryCursorPayload(
    val version: Int = 2,
    val surface: String,
    val scopeHash: String,
    val boostBucket: Int? = null,
    val orderBucket: Int? = null,
    val orderTiebreak: Long? = null,
    val userId: String? = null,
    val seenCount: Int? = null,
)
