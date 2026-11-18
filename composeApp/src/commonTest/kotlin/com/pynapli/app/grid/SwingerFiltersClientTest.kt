package com.pynapli.app.grid

import com.pynapli.app.profile.lifestyleRows
import com.pynapli.app.profile.partnerPreviewSummary
import com.pynapli.app.swipe.SwipeFilters
import com.pynapli.app.tonight.TonightFeedFilters
import com.pynapli.shared.network.dto.LinkedPartnerMiniDto
import com.pynapli.shared.network.dto.SwipeDealbreakerKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Round 4 client mapping: boundaries + experience round-trip and render the same everywhere. */
class SwingerFiltersClientTest {
    @Test
    fun gridBoundariesAndExperienceRoundTrip() {
        val filters =
            GridFilters(
                lifestyleTags = setOf(LifestyleTag.SoftSwap, LifestyleTag.SomeExperience),
                boundaryItems = setOf(Boundary.Photos, Boundary.SameDay),
            )
        val request = filters.toGridQueryRequest(cursor = null)
        assertEquals(setOf("soft_swap", "some_experience"), request.lifestyleTags.toSet())
        assertEquals(setOf("photos", "same_day"), request.boundaryItems.toSet())
        assertEquals(filters.boundaryItems, GridFilters.fromGridQueryRequest(request).boundaryItems)
        assertEquals(2, filters.activeCount)
    }

    @Test
    fun swipeBoundariesAndSwingerDealbreakersRoundTrip() {
        val filters =
            SwipeFilters(
                lifestyleTags = setOf(LifestyleTag.FullSwap, LifestyleTag.SameRoom),
                boundaryItems = setOf(Boundary.Groups),
                dealbreakers = setOf(SwipeDealbreakerKey.SWAP, SwipeDealbreakerKey.BOUNDARIES),
            )
        val request = filters.toSwipeDeckRequest()
        assertEquals(listOf("groups"), request.boundaryItems)
        val back = SwipeFilters.fromSwipeDeckRequest(request)
        assertEquals(filters.boundaryItems, back.boundaryItems)
        assertEquals(filters.dealbreakers, back.dealbreakers)
    }

    @Test
    fun tonightFiltersDropLifestyleWithoutAgeVerification() {
        val filters =
            TonightFeedFilters(
                orientations = setOf("bisexual"),
                lifestyleTags = setOf(LifestyleTag.SoftSwap),
                boundaryItems = setOf(Boundary.HomeMeets),
            )
        val dto = filters.withLifestyleAccess(allowed = false).toDto()
        assertEquals(listOf("bisexual"), dto.orientations)
        assertEquals(emptyList(), dto.lifestyleTags)
        assertEquals(listOf("home_meets"), dto.boundaryItems)
        assertEquals(3, filters.activeCount)
    }

    @Test
    fun profileLifestyleBlockGroupsSwingerFirst() {
        val rows =
            lifestyleRows(
                experienceLevel = "some_experience",
                lifestyleTags = listOf("enm", "soft_swap", "same_room"),
                boundaries = listOf("photos:not_for_me", "same_day:ask_first", "groups:not_for_me", "junk"),
            )
        assertEquals(
            listOf(
                "Experience" to listOf("Some experience"),
                "Swap" to listOf("Soft swap"),
                "Room" to listOf("Same-room"),
                "Lifestyle" to listOf("ENM"),
                "Ask first" to listOf("Meeting the same day"),
                "Not for me" to listOf("Photos or video", "Group settings"),
            ),
            rows.map { it.label to it.values },
        )
    }

    @Test
    fun partnerPreviewShowsThePartnersOwnOrientationAndLifestyle() {
        val partner =
            LinkedPartnerMiniDto(
                userId = "p",
                orientation = "bisexual",
                experienceLevel = "experienced",
                lifestyleTags = listOf("soft_swap"),
            )
        assertEquals("Bisexual · Experienced · Soft swap", partnerPreviewSummary(partner))
        assertNull(partnerPreviewSummary(LinkedPartnerMiniDto(userId = "p")))
    }
}
