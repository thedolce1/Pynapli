package com.pynapli.server.discovery

import com.pynapli.server.grid.GridCursorAnchor
import org.junit.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Pins the grid pagination-cursor security contract directly on the codec: cursors are
 * HMAC-signed (tamper-evident), bound to the issuing viewer + filter scope (a cursor cannot be
 * replayed by another viewer or against different filters), and reject malformed input.
 */
class DiscoveryCursorCodecTest {
    private val secret = "test-discovery-cursor-secret"
    private val codec = DiscoveryCursorCodec(secret)
    private val viewer = UUID.randomUUID()
    private val anchor =
        GridCursorAnchor(boostBucket = 2, distanceBucketMeters = 1_500, userId = UUID.randomUUID(), seenCount = 40)

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
    fun rejectsTamperedPayload() {
        val tampered = tamperPayloadSegment(codec.encodeGridCursor(scope(), anchor))
        assertFailsWith<DiscoveryCursorException> { codec.decodeGridCursor(tampered, scope()) }
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
    fun rejectsMalformedTokens() {
        assertFailsWith<DiscoveryCursorException> { codec.decodeGridCursor("not-a-token", scope()) }
        assertFailsWith<DiscoveryCursorException> { codec.decodeGridCursor("v1.only-two-parts", scope()) }
        // Correct shape, wrong version prefix.
        val token = codec.encodeGridCursor(scope(), anchor)
        val parts = token.split(".")
        assertFailsWith<DiscoveryCursorException> {
            codec.decodeGridCursor("v2.${parts[1]}.${parts[2]}", scope())
        }
    }

    private fun tamperPayloadSegment(token: String): String {
        val parts = token.split(".")
        val payload = parts[1]
        val flipped = payload.dropLast(1) + if (payload.last() == 'A') 'B' else 'A'
        return "${parts[0]}.$flipped.${parts[2]}"
    }
}
