package com.pynapli.shared.presence

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class LastActiveBucketTest {
    @Test
    fun bucketsAreCoarseAndOnlineWins() {
        assertEquals(LastActiveBucket.OnlineNow, LastActiveBucket.bucketFor(online = true, sinceLastActive = 3.days))
        assertEquals(LastActiveBucket.Recent, LastActiveBucket.bucketFor(online = false, sinceLastActive = 14.minutes))
        assertEquals(LastActiveBucket.Today, LastActiveBucket.bucketFor(online = false, sinceLastActive = 16.minutes))
        assertEquals(LastActiveBucket.Today, LastActiveBucket.bucketFor(online = false, sinceLastActive = 23.hours))
        assertEquals(LastActiveBucket.ThisWeek, LastActiveBucket.bucketFor(online = false, sinceLastActive = 6.days))
        assertNull(LastActiveBucket.bucketFor(online = false, sinceLastActive = 8.days))
        assertNull(LastActiveBucket.bucketFor(online = false, sinceLastActive = null))
    }

    @Test
    fun labelsComeFromKeysAndUnknownKeysShowNothing() {
        assertEquals("Active today", LastActiveBucket.labelFor("active_today"))
        assertEquals("Online now", LastActiveBucket.labelFor("online_now"))
        assertNull(LastActiveBucket.labelFor("2026-11-18T10:00:00Z"))
        assertNull(LastActiveBucket.labelFor(null))
    }
}
