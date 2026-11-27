package com.pynapli.app.profile

import com.pynapli.app.grid.GridProfileCardUi
import com.pynapli.shared.profile.ProfileTagSet
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** G3-A: the profile peek lists the tags the viewer shares first, and flags them. */
class ProfileInCommonTagsTest {
    @Test
    fun eachLifestyleRowListsSharedTagsFirst() {
        val viewer = ProfileTagSet(lifestyleTags = listOf("full_swap", "enm"), experienceLevel = "experienced")

        val rows =
            lifestyleRows(
                experienceLevel = "experienced",
                lifestyleTags = listOf("soft_swap", "full_swap", "same_room", "swinging", "enm"),
                boundaries = listOf("photos:not_for_me"),
                viewerTags = viewer,
            )

        assertEquals(
            listOf(
                "Experience" to listOf("Experienced"),
                "Swap" to listOf("Full swap", "Soft swap"),
                "Room" to listOf("Same-room"),
                "Lifestyle" to listOf("ENM", "Swinging"),
                "Not for me" to listOf("Photos or video"),
            ),
            rows.map { it.label to it.values },
        )
        assertEquals(
            listOf(setOf("Experienced"), setOf("Full swap"), emptySet(), setOf("ENM"), emptySet()),
            rows.map { it.inCommon },
            "boundaries are never flagged",
        )
    }

    @Test
    fun withoutTheViewersTagsTheOrderIsTheProfilesOwn() {
        val rows = lifestyleRows(experienceLevel = null, lifestyleTags = listOf("soft_swap", "full_swap"), boundaries = emptyList())
        assertEquals(listOf("Soft swap", "Full swap"), rows.single().values)
        assertTrue(rows.single().inCommon.isEmpty())
    }

    @Test
    fun theViewersTagsAreReadWhenAProfileOpensAndNeverForTheOwnPreview() =
        runTest {
            val viewer = ProfileTagSet(lifestyleTags = listOf("soft_swap"))
            var reads = 0
            val holder =
                ProfileDetailStateHolder(
                    repository = SamplePublicProfileRepository(),
                    viewerTags = {
                        reads += 1
                        viewer
                    },
                )

            holder.open(inCommonCard("someone"))
            assertEquals(viewer, holder.state.value.viewerTags)

            holder.open(inCommonCard("me"), isOwnProfile = true)
            assertNull(holder.state.value.viewerTags, "previewing yourself, everything would be in common")
            assertEquals(1, reads)
        }
}

private fun inCommonCard(id: String) = GridProfileCardUi(id = id, displayName = id, online = false, distanceLabel = null)
