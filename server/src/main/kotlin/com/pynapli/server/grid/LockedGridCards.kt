package com.pynapli.server.grid

import com.pynapli.server.distance.toPublicDistanceLabel
import com.pynapli.server.distance.toPublicDistanceMeters
import com.pynapli.server.media.MediaAssetUrlResolver
import com.pynapli.shared.network.dto.GridCardDto
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Opaque per-viewer ids for Section 3 locked Grid cards: `locked_` + base64url of the first 16 bytes
 * of HMAC-SHA256(viewer id || card user id). The same viewer always gets the same token for the same
 * person (list keys don't churn); nobody can reverse it, and another viewer's token differs.
 *
 * The key is derived from the discovery cursor secret under its own purpose label (no new config),
 * so it is independent of the cursor encryption key. A token is never a UUID: the `locked_` prefix
 * puts non-hex characters in the first dash-delimited group, so `UUID.fromString` (every route's id
 * parser) always rejects it, and the presence stream drops it.
 */
class LockedGridCardTokens(
    secret: String,
) {
    private val key: SecretKeySpec =
        secret
            .also { require(it.isNotBlank()) { "Locked card token secret must not be blank." } }
            .toByteArray(StandardCharsets.UTF_8)
            .let { secretBytes ->
                val mac = Mac.getInstance(HMAC)
                mac.init(SecretKeySpec(secretBytes, HMAC))
                SecretKeySpec(mac.doFinal(KEY_DERIVATION_LABEL.toByteArray(StandardCharsets.UTF_8)), HMAC)
            }

    fun tokenFor(
        viewerUserId: UUID,
        cardUserId: UUID,
    ): String {
        // Fixed-length input: 16 bytes of viewer id then 16 bytes of card user id.
        val input =
            ByteBuffer
                .allocate(UUID_BYTES * 2)
                .putLong(viewerUserId.mostSignificantBits)
                .putLong(viewerUserId.leastSignificantBits)
                .putLong(cardUserId.mostSignificantBits)
                .putLong(cardUserId.leastSignificantBits)
                .array()
        val mac = Mac.getInstance(HMAC)
        mac.init(key)
        val digest = mac.doFinal(input).copyOf(TOKEN_BYTES)
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    companion object {
        const val PREFIX = "locked_"
        private const val HMAC = "HmacSHA256"
        private const val KEY_DERIVATION_LABEL = "pynapli/grid-locked-card/hmac/v1"
        private const val UUID_BYTES = 16
        private const val TOKEN_BYTES = 16
    }
}

/**
 * The Section 3 locked tile, built field by field from an allowlist (never a copy of the unlocked
 * card, so a field added to [GridCardDto] later stays empty here by default). It keeps only what a
 * tile shows: the approved thumbnail (same URL rules as an unlocked card), the online dot, the public
 * distance bucket when the member shows distance, and the badges (visiting, Tonight, verified,
 * couple-verified, fresh). Stripped: the user id and rank token (both become [token]), the display
 * name, the linked-partner mini (partner id, name, thumbnail), activeAgo, and the visiting-soon dates.
 */
internal fun GridCardRecord.toLockedGridCardDto(
    token: String,
    onlineUserIds: Set<UUID>,
    mediaAssetUrlResolver: MediaAssetUrlResolver?,
): GridCardDto =
    GridCardDto(
        userId = token,
        displayName = "",
        thumbnailUrl = thumbnailMediaAssetId?.let { mediaAssetUrlResolver?.publicContentUrl(it) },
        online = userId in onlineUserIds,
        distanceLabel = if (showDistance) distanceMeters.toPublicDistanceLabel() else null,
        distanceMeters = if (showDistance) distanceMeters.toPublicDistanceMeters() else null,
        linkedPartner = null,
        rankToken = token,
        visiting = visiting,
        tonightActive = tonightActive,
        visitingFrom = null,
        visitingUntil = null,
        verified = verified,
        coupleVerified = coupleVerified,
        fresh = fresh,
        activeAgo = null,
        locked = true,
    )
