package com.pynapli.server.grid

import com.pynapli.server.distance.toPublicDistanceLabel
import com.pynapli.server.media.MediaAssetUrlResolver
import com.pynapli.server.presence.parseSubscription
import com.pynapli.shared.network.dto.GridCardDto
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Section 3 S3A-2: the opaque locked-card token and the identity-free locked tile. */
class LockedGridCardsTest {
    private val tokens = LockedGridCardTokens("test-discovery-cursor-secret")
    private val viewer = UUID.fromString("00000000-0000-0000-0000-00000000aa01")
    private val member = UUID.fromString("00000000-0000-0000-0000-00000000bb02")
    private val partner = UUID.fromString("00000000-0000-0000-0000-00000000cc03")
    private val thumbnail = UUID.fromString("00000000-0000-0000-0000-00000000dd04")
    private val partnerThumbnail = UUID.fromString("00000000-0000-0000-0000-00000000ee05")

    @Test
    fun tokenIsStablePerViewerAndDiffersAcrossViewersMembersAndSecrets() {
        val token = tokens.tokenFor(viewer, member)
        assertEquals(token, tokens.tokenFor(viewer, member))
        assertTrue(token.startsWith("locked_"))
        assertEquals("locked_".length + 22, token.length)
        assertNotEquals(token, tokens.tokenFor(UUID.randomUUID(), member))
        assertNotEquals(token, tokens.tokenFor(viewer, UUID.randomUUID()))
        // Swapping the two ids gives a different token (fixed-length, ordered input).
        assertNotEquals(token, tokens.tokenFor(member, viewer))
        assertNotEquals(token, LockedGridCardTokens("another-secret").tokenFor(viewer, member))
        assertFalse(token.contains(member.toString()))
        assertFalse(token.contains(viewer.toString()))
    }

    @Test
    fun noTokenEverParsesAsAUuidSoNoRouteAcceptsOneAndPresenceDropsThem() {
        val generated = List(10_000) { tokens.tokenFor(UUID.randomUUID(), UUID.randomUUID()) }
        generated.forEach { token ->
            assertTrue(runCatching { UUID.fromString(token) }.isFailure, "token parsed as a UUID: $token")
            assertTrue(runCatching { UUID.fromString(token.trim()) }.isFailure)
        }
        // The presence stream's subscription parser drops every locked token and keeps real ids.
        assertEquals(setOf(member), parseSubscription(generated.take(300) + member.toString()))
    }

    @Test
    fun lockedCardCarriesOnlyWhatATileShows() {
        val record =
            GridCardRecord(
                userId = member,
                displayName = "Jordan and Sam",
                thumbnailMediaAssetId = thumbnail,
                showDistance = true,
                distanceMeters = 3_400.0,
                linkedPartnerUserId = partner,
                linkedPartnerDisplayName = "Sam",
                linkedPartnerThumbnailMediaAssetId = partnerThumbnail,
                visiting = true,
                tonightActive = true,
                visitingFrom = LocalDate.parse("2026-12-01"),
                visitingUntil = LocalDate.parse("2026-12-04"),
                verified = true,
                coupleVerified = true,
                fresh = true,
                lastActiveAt = Instant.now(),
                activityHidden = false,
            )
        val token = tokens.tokenFor(viewer, member)

        val card = record.toLockedGridCardDto(token, onlineUserIds = setOf(member), mediaAssetUrlResolver = UrlResolver)

        assertTrue(card.locked)
        assertEquals(token, card.userId)
        assertEquals(token, card.rankToken)
        assertEquals("", card.displayName)
        assertNull(card.linkedPartner)
        assertNull(card.activeAgo)
        assertNull(card.visitingFrom)
        assertNull(card.visitingUntil)
        // What the tile shows stays.
        assertEquals("https://media.example/$thumbnail", card.thumbnailUrl)
        assertTrue(card.online)
        assertEquals(3_400.0.toPublicDistanceLabel(), card.distanceLabel)
        assertEquals(3_000, card.distanceMeters)
        assertTrue(card.visiting && card.tonightActive && card.verified && card.coupleVerified && card.fresh)

        // Nothing in the serialized card names the member or the partner.
        val json = Json.encodeToString(GridCardDto.serializer(), card)
        listOf(member.toString(), partner.toString(), partnerThumbnail.toString(), "Jordan", "Sam", "grid:v1").forEach {
            assertFalse(json.contains(it), "locked card JSON leaks $it: $json")
        }
    }

    @Test
    fun lockedCardHidesDistanceWhenTheMemberHidesIt() {
        val record =
            GridCardRecord(
                userId = member,
                displayName = "Alex",
                showDistance = false,
                distanceMeters = 800.0,
            )

        val card = record.toLockedGridCardDto(tokens.tokenFor(viewer, member), emptySet(), UrlResolver)

        assertNull(card.distanceLabel)
        assertNull(card.distanceMeters)
        assertNull(card.thumbnailUrl)
        assertFalse(card.online)
    }

    private object UrlResolver : MediaAssetUrlResolver {
        override fun publicContentUrl(mediaAssetId: UUID): String = "https://media.example/$mediaAssetId"
    }
}
