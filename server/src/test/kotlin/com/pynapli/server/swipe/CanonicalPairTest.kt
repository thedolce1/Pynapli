package com.pynapli.server.swipe

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class CanonicalPairTest {
    @Test
    fun canonicalPairUsesPostgresUuidTextOrdering() {
        val highSignedMostBits = UUID.fromString("fc69d1da-bad6-4fa9-8dee-229ac07abb4c")
        val lowerTextValue = UUID.fromString("4622e903-0964-4c63-80e4-d1402e683ef6")

        val (first, second) = canonicalPair(highSignedMostBits, lowerTextValue)

        assertEquals(lowerTextValue, first)
        assertEquals(highSignedMostBits, second)
    }
}
