package com.pynapli.server.discovery

import com.pynapli.server.grid.GridCursorAnchor
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Pins the grid pagination-cursor security contract directly on the codec: cursors are opaque
 * (authenticated encryption — nothing in the token is readable, L-07), tamper-evident, bound to the
 * issuing viewer + filter scope (a cursor cannot be replayed by another viewer or against different
 * filters), and reject malformed or old-format input as an invalid cursor.
 */
class DiscoveryCursorCodecTest {
    private val secret = "test-discovery-cursor-secret"
    private val codec = DiscoveryCursorCodec(secret)
    private val viewer = UUID.randomUUID()
    private val anchor =
        GridCursorAnchor(boostBucket = 1, orderBucket = 1_234, userId = UUID.randomUUID(), seenCount = 40)

    private fun scope(
        viewerUserId: UUID = viewer,
        filters: DiscoveryFilters = DiscoveryFilters(),
    ): String =
        DiscoveryCursorCodec.gridScopeHash(
            viewerUserId = viewerUserId,
            radiusMeters = 50_000,
            limit = 30,
            filters = filters,
            onlineOnly = false,
        )

    @Test
    fun roundTripsWithinTheSameScope() {
        val decoded = codec.decodeGridCursor(codec.encodeGridCursor(scope(), anchor), scope())
        assertEquals(anchor, decoded)
    }

    @Test
    fun cursorIsOpaqueAndRevealsNothing() {
        // The token is random-looking, so this runs many times and every needle must be impossible or
        // vanishingly unlikely by chance: JSON punctuation is outside the base64url alphabet, and the
        // raw (still encrypted) box bytes are only searched for long needles. A lone '{' or a 4-digit
        // string in random bytes is a coin flip over enough runs, not a leak.
        val tokenNeedles =
            listOf("{", "\"", ":", "distance", "boostBucket", "orderBucket", "userId", "seenCount", anchor.userId.toString())
        val boxNeedles =
            listOf(
                "\"boostBucket\"",
                "\"orderBucket\"",
                "\"orderBucket\":1234",
                "\"userId\"",
                "\"seenCount\"",
                "\"scopeHash\"",
                "\"surface\":\"grid\"",
                anchor.userId.toString(),
                scope(),
            )
        repeat(OPACITY_ITERATIONS) {
            val token = codec.encodeGridCursor(scope(), anchor)
            val segments = token.split(".")
            assertEquals(2, segments.size, "token is <version>.<sealed box>: $token")
            // base64url text: no JSON punctuation, field names, ids or anchor numbers.
            tokenNeedles.forEach { assertFalse(token.contains(it), "cursor token leaks '$it'") }
            val asText = String(Base64.getUrlDecoder().decode(segments[1]), StandardCharsets.ISO_8859_1)
            boxNeedles.forEach { assertFalse(asText.contains(it), "cursor box leaks '$it'") }
            // Randomised nonce: the same anchor never encodes to the same token, so two cursors cannot be
            // compared to learn that they point at the same card or the same distance.
            assertNotEquals(token, codec.encodeGridCursor(scope(), anchor))
        }
    }

    @Test
    fun rejectsTamperedPayload() {
        val token = codec.encodeGridCursor(scope(), anchor)
        val parts = token.split(".")
        val body = Base64.getUrlDecoder().decode(parts[1])
        for (index in body.indices step 7) {
            val flipped = body.copyOf().also { it[index] = (it[index].toInt() xor 0x01).toByte() }
            val tampered = "${parts[0]}.${Base64.getUrlEncoder().withoutPadding().encodeToString(flipped)}"
            assertFailsWith<DiscoveryCursorException> { codec.decodeGridCursor(tampered, scope()) }
        }
    }

    @Test
    fun rejectsForeignSecret() {
        val token = codec.encodeGridCursor(scope(), anchor)
        val foreign = DiscoveryCursorCodec("a-different-secret")
        assertFailsWith<DiscoveryCursorException> { foreign.decodeGridCursor(token, scope()) }
    }

    @Test
    fun rejectsCursorReplayedByAnotherViewer() {
        // Same filters, only the viewer differs: proves the cursor is viewer-bound via the scope hash.
        val token = codec.encodeGridCursor(scope(viewer), anchor)
        assertFailsWith<DiscoveryCursorException> {
            codec.decodeGridCursor(token, scope(UUID.randomUUID()))
        }
    }

    @Test
    fun rejectsCursorFromADifferentFilterScope() {
        val token = codec.encodeGridCursor(scope(filters = DiscoveryFilters()), anchor)
        assertFailsWith<DiscoveryCursorException> {
            codec.decodeGridCursor(token, scope(filters = DiscoveryFilters(verifiedOnly = true)))
        }
    }

    @Test
    fun lifestyleLevelAndBoundaryFiltersAreInTheCursorScope() {
        val base = scope()
        assertNotEquals(base, scope(filters = DiscoveryFilters(lifestyleTags = setOf("some_experience"))))
        assertNotEquals(base, scope(filters = DiscoveryFilters(boundaryItems = setOf("same_day"))))
        assertNotEquals(
            scope(filters = DiscoveryFilters(boundaryItems = setOf("same_day"))),
            scope(filters = DiscoveryFilters(boundaryItems = setOf("groups"))),
        )
    }

    @Test
    fun rejectsMalformedTokens() {
        assertFailsWith<DiscoveryCursorException> { codec.decodeGridCursor("", scope()) }
        assertFailsWith<DiscoveryCursorException> { codec.decodeGridCursor("not-a-token", scope()) }
        assertFailsWith<DiscoveryCursorException> { codec.decodeGridCursor("g2.", scope()) }
        assertFailsWith<DiscoveryCursorException> { codec.decodeGridCursor("g2.AAAA", scope()) }
        assertFailsWith<DiscoveryCursorException> { codec.decodeGridCursor("g2.!!not-base64!!", scope()) }
        // Correct body, wrong version prefix.
        val parts = codec.encodeGridCursor(scope(), anchor).split(".")
        assertFailsWith<DiscoveryCursorException> { codec.decodeGridCursor("g3.${parts[1]}", scope()) }
    }

    @Test
    fun rejectsOldReadableV1CursorsAsInvalid() {
        // The pre-L-07 format: v1.<base64 JSON>.<HMAC>, correctly signed with the same secret. It must be
        // refused as an invalid cursor (never decoded, never a crash).
        val json =
            """{"version":1,"surface":"grid","scopeHash":"${scope()}","boostBucket":1,""" +
                """"orderBucket":1234,"userId":"${anchor.userId}","seenCount":40}"""
        val b64 = Base64.getUrlEncoder().withoutPadding()
        val signed = "v1.${b64.encodeToString(json.toByteArray(StandardCharsets.UTF_8))}"
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret.toByteArray(), "HmacSHA256")) }
        val legacy = "$signed.${b64.encodeToString(mac.doFinal(signed.toByteArray(StandardCharsets.UTF_8)))}"
        assertTrue(legacy.startsWith("v1."))
        assertFailsWith<DiscoveryCursorException> { codec.decodeGridCursor(legacy, scope()) }
    }

    private companion object {
        const val OPACITY_ITERATIONS = 200
    }
}
