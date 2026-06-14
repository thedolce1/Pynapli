package com.pynapli.app.profilehub

import com.pynapli.shared.network.dto.DiscoverySettingsDto
import com.pynapli.shared.network.dto.ProfileDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ProfileHubDraftMappingTest {
    @Test
    fun draftRoundTripsBioTagsAndSelfDescribe() {
        val draft =
            testProfileDto(
                bio = "Social-first couple-adjacent human.",
                gender = "self_describe",
                genderDetail = "Genderfluid",
                orientation = "queer",
                lifestyleTags = listOf("enm", "soft_swap"),
                logisticsTags = listOf("hosts"),
                intents = listOf("events"),
            ).toDraft()

        assertEquals("Social-first couple-adjacent human.", draft.bio)
        assertEquals("self_describe", draft.gender)
        assertEquals("Genderfluid", draft.genderDetail)
        assertEquals("queer", draft.orientation)
        assertEquals(setOf("enm", "soft_swap"), draft.lifestyleTags)
        assertEquals(setOf("hosts"), draft.logisticsTags)
        assertEquals(setOf("events"), draft.intents)

        val request = draft.toRequest()
        assertEquals("Social-first couple-adjacent human.", request.bio)
        assertEquals("self_describe", request.gender)
        assertEquals("Genderfluid", request.genderDetail)
        assertEquals(listOf("hosts"), request.logisticsTags)
        assertEquals(listOf("events"), request.intents)
        assertEquals(setOf("enm", "soft_swap"), request.lifestyleTags?.toSet())
    }

    @Test
    fun legacyFreeTextGenderStartsUnsetInsteadOfRenderingABogusChip() {
        val draft = testProfileDto(gender = "couple seeking", orientation = "open").toDraft()

        assertEquals("", draft.gender)
        assertEquals("", draft.orientation)
    }

    @Test
    fun emptyBioAndTagsAreSentAsExplicitClears() {
        val request = testProfileDto().toDraft().toRequest()

        assertEquals("", request.bio)
        assertEquals("", request.neighborhood)
        assertEquals(emptyList(), request.lifestyleTags)
        assertEquals(emptyList(), request.logisticsTags)
        assertEquals(emptyList(), request.intents)
    }

    @Test
    fun neighborhoodRoundTripsAndValidatesLength() {
        val draft = testProfileDto(neighborhood = "Mission").toDraft()
        assertEquals("Mission", draft.neighborhood)
        assertEquals("Mission", draft.toRequest().neighborhood)

        val base = testProfileDto(displayName = "Alex").toDraft()
        assertNull(base.copy(neighborhood = "Mission").validationError())
        assertNotNull(base.copy(neighborhood = "x".repeat(51)).validationError())
    }

    @Test
    fun privacyDraftRoundTripsPartnerVisibilityAndFallsBackForUnknown() {
        val draft = discoverySettingsDto(partnerVisibility = "matches_only").toPrivacyDraft()
        assertEquals("matches_only", draft.partnerVisibility)
        assertEquals("matches_only", draft.toRequest().partnerVisibility)

        // An unrecognized stored value falls back to the safe default in the picker.
        assertEquals("public", discoverySettingsDto(partnerVisibility = "friends").toPrivacyDraft().partnerVisibility)
    }

    @Test
    fun validationCatchesOverlongBioAndMissingSelfDescribeLabel() {
        val base = testProfileDto(displayName = "Alex").toDraft()

        assertNull(base.validationError())
        assertNotNull(base.copy(bio = "x".repeat(501)).validationError())
        assertNotNull(base.copy(gender = "self_describe", genderDetail = " ").validationError())
        assertNotNull(base.copy(orientation = "self_describe", orientationDetail = "y".repeat(41)).validationError())
        assertNull(base.copy(gender = "self_describe", genderDetail = "Genderfluid").validationError())
    }
}

private fun discoverySettingsDto(partnerVisibility: String): DiscoverySettingsDto =
    DiscoverySettingsDto(
        gridVisible = true,
        swipeVisible = true,
        eventsVisible = true,
        showDistance = true,
        showInViewedLists = true,
        incognito = false,
        allowGridMessages = true,
        gridMessagePolicy = "everyone",
        allowFlirts = true,
        allowIntroNotes = true,
        allowAlbumRequests = true,
        soloEnabled = true,
        togetherEnabled = false,
        socialEnabled = true,
        partnerVisibility = partnerVisibility,
    )

private fun testProfileDto(
    displayName: String = "Alex",
    bio: String? = null,
    neighborhood: String? = null,
    gender: String? = null,
    genderDetail: String? = null,
    orientation: String? = null,
    lifestyleTags: List<String> = emptyList(),
    logisticsTags: List<String> = emptyList(),
    intents: List<String> = emptyList(),
): ProfileDto =
    ProfileDto(
        userId = "user-1",
        displayName = displayName,
        bio = bio,
        neighborhood = neighborhood,
        gender = gender,
        genderDetail = genderDetail,
        orientation = orientation,
        lifestyleTags = lifestyleTags,
        logisticsTags = logisticsTags,
        intents = intents,
        discoveryStyle = "both",
        profileCompletenessScore = 50,
        moderationStatus = "approved",
        visibilityStatus = "visible",
    )
