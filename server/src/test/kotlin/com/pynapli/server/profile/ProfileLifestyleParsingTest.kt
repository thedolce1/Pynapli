package com.pynapli.server.profile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProfileLifestyleParsingTest {
    @Test
    fun explicitExperienceLevelWinsAndBlankClears() {
        val set = parseLifestyle(lifestyleTags = listOf("soft_swap"), experienceLevel = "some_experience")
        assertEquals(listOf("soft_swap"), set.tags)
        assertTrue(set.experienceProvided)
        assertEquals("some_experience", set.experienceLevel)

        val cleared = parseLifestyle(lifestyleTags = null, experienceLevel = "")
        assertTrue(cleared.experienceProvided)
        assertNull(cleared.experienceLevel)
    }

    @Test
    fun omittedExperienceLeavesTheLevelUnchanged() {
        val parsed = parseLifestyle(lifestyleTags = listOf("enm"), experienceLevel = null)
        assertFalse(parsed.experienceProvided)
        assertEquals(listOf("enm"), parsed.tags)
    }

    @Test
    fun olderClientsSendingExperienceAsATagStillSetOneLevel() {
        val one = parseLifestyle(lifestyleTags = listOf("experienced", "full_swap"), experienceLevel = null)
        assertEquals(listOf("full_swap"), one.tags)
        assertTrue(one.experienceProvided)
        assertEquals("experienced", one.experienceLevel)

        // Both old tags are contradictory: the level is cleared, not guessed.
        val both = parseLifestyle(lifestyleTags = listOf("new_to_lifestyle", "experienced"), experienceLevel = null)
        assertEquals(emptyList(), both.tags)
        assertTrue(both.experienceProvided)
        assertNull(both.experienceLevel)
    }

    @Test
    fun unknownExperienceOrTagIsRejected() {
        assertFailsWith<ProfileException> { parseLifestyle(lifestyleTags = null, experienceLevel = "expert") }
        assertFailsWith<ProfileException> { parseLifestyle(lifestyleTags = listOf("anything_goes"), experienceLevel = null) }
    }

    @Test
    fun boundariesAreCuratedAndOneStancePerItem() {
        assertEquals(listOf("photos:ask_first", "groups:not_for_me"), listOf("Photos:Ask_First", "groups:not_for_me").cleanBoundaries())
        assertNull((null as List<String>?).cleanBoundaries())
        assertFailsWith<ProfileException> { listOf("photos:ask_first", "photos:not_for_me").cleanBoundaries() }
        assertFailsWith<ProfileException> { listOf("no strangers please").cleanBoundaries() }
    }
}
