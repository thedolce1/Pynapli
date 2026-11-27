package com.pynapli.shared.profile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InCommonTagsTest {
    @Test
    fun sharedTagsComeFirstEachHalfInTheProfilesOrder() {
        val ordered = inCommonFirst(theirs = listOf("enm", "soft_swap", "same_room", "full_swap"), mine = setOf("same_room", "enm"))

        assertEquals(listOf("enm", "same_room", "soft_swap", "full_swap"), ordered.map { it.token })
        assertEquals(listOf(true, true, false, false), ordered.map { it.inCommon })
    }

    @Test
    fun itNeverAddsATagTheProfileDoesNotCarry() {
        val ordered = inCommonFirst(theirs = listOf("soft_swap"), mine = setOf("soft_swap", "full_swap", "same_room"))
        assertEquals(listOf(ProfileTagMatch("soft_swap", inCommon = true)), ordered)
        assertEquals(emptyList(), inCommonFirst(theirs = emptyList(), mine = setOf("soft_swap")))
    }

    @Test
    fun blanksAndRepeatsAreDropped() {
        val ordered = inCommonFirst(theirs = listOf("enm", "", "enm", " ", "travel"), mine = listOf("", "travel"))
        assertEquals(listOf("travel", "enm"), ordered.map { it.token })
        assertEquals(listOf(true, false), ordered.map { it.inCommon }, "a blank never matches a blank")
    }

    @Test
    fun theWholeProfileIsOrderedAndCounted() {
        val viewer =
            ProfileTagSet(
                lifestyleTags = listOf("soft_swap"),
                experienceLevel = "experienced",
                logisticsTags = listOf("can_host"),
                intents = listOf("friends", "dates"),
            )
        val profile =
            ProfileTagSet(
                lifestyleTags = listOf("same_room", "soft_swap"),
                experienceLevel = "experienced",
                logisticsTags = listOf("can_travel", "can_host"),
                intents = listOf("dates", "events"),
            )

        val tags = inCommonTags(viewer, profile)

        assertEquals(listOf("soft_swap", "same_room"), tags.lifestyleTags.map { it.token })
        assertTrue(tags.experienceInCommon)
        assertEquals(listOf("can_host", "can_travel"), tags.logisticsTags.map { it.token })
        assertEquals(listOf("dates", "events"), tags.intents.map { it.token })
        assertEquals(4, tags.count)
    }

    @Test
    fun noViewerFlagsNothingAndKeepsTheProfilesOrder() {
        val profile = ProfileTagSet(lifestyleTags = listOf("same_room", "soft_swap"), experienceLevel = "new", intents = listOf("dates"))

        val tags = inCommonTags(viewer = null, profile = profile)

        assertEquals(listOf("same_room", "soft_swap"), tags.lifestyleTags.map { it.token })
        assertTrue(tags.lifestyleTags.none { it.inCommon })
        assertFalse(tags.experienceInCommon)
        assertEquals(0, tags.count)
    }

    @Test
    fun anUnsetExperienceIsNeverInCommon() {
        val tags = inCommonTags(viewer = ProfileTagSet(experienceLevel = null), profile = ProfileTagSet(experienceLevel = null))
        assertFalse(tags.experienceInCommon)
    }
}
