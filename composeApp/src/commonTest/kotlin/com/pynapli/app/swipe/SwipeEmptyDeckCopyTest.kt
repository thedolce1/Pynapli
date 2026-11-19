package com.pynapli.app.swipe

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SwipeEmptyDeckCopyTest {
    @Test
    fun verifiedCouplesOnlySaysNoVerifiedCouplesAndNeverMentionsDealbreakers() {
        // The server never relaxes this filter, so the empty deck must not hint that a lock is the cause.
        val copy = emptyDeckCopy(filtersActive = true, verifiedCouplesOnly = true)
        assertEquals("No verified couples nearby yet", copy.title)
        assertTrue("selfie-verified" in copy.message)
        assertTrue("Widen distance" in copy.message)
        assertFalse("dealbreaker" in copy.message)
    }

    @Test
    fun otherFiltersKeepTheExistingWords() {
        assertEquals("No one else matches your filters", emptyDeckCopy(filtersActive = true, verifiedCouplesOnly = false).title)
        assertEquals("You've seen everyone nearby", emptyDeckCopy(filtersActive = false, verifiedCouplesOnly = false).title)
    }
}
