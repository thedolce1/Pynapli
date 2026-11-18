package com.pynapli.app.grid

import com.pynapli.shared.profile.DiscoveryTagTaxonomies
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Binds the client filter/profile enums to the shared canonical token vocabulary
 * ([DiscoveryTagTaxonomies]) so the profile ↔ filter loop can never silently drift:
 * a profile may only carry values a filter can match, and the server validates writes +
 * filters against the same shared sets.
 */
class DiscoveryTaxonomiesAlignmentTest {
    @Test
    fun lifestyleTagServerValuesMatchSharedTaxonomy() {
        assertEquals(
            DiscoveryTagTaxonomies.lifestyleFilterTags,
            LifestyleTag.entries.map { it.serverValue }.toSet(),
        )
        // Groups line up with the server's grouped matching (any-of inside, all-of across).
        assertEquals(DiscoveryTagTaxonomies.swapPreferences, LifestyleTag.inGroup(LifestyleGroup.Swap).map { it.serverValue }.toSet())
        assertEquals(DiscoveryTagTaxonomies.roomPreferences, LifestyleTag.inGroup(LifestyleGroup.Room).map { it.serverValue }.toSet())
        assertEquals(DiscoveryTagTaxonomies.experienceLevels, LifestyleTag.inGroup(LifestyleGroup.Experience).map { it.serverValue })
        assertEquals(DiscoveryTagTaxonomies.otherLifestyleTags, LifestyleTag.inGroup(LifestyleGroup.Other).map { it.serverValue }.toSet())
    }

    @Test
    fun boundaryValuesMatchSharedTaxonomy() {
        assertEquals(DiscoveryTagTaxonomies.boundaryItems, Boundary.entries.map { it.serverValue })
        assertEquals(DiscoveryTagTaxonomies.boundaryStances, BoundaryStance.entries.map { it.serverValue }.toSet())
        val tokens = Boundary.entries.flatMap { b -> BoundaryStance.entries.map { it.tokenFor(b) } }.toSet()
        assertEquals(DiscoveryTagTaxonomies.boundaryTokens, tokens)
    }

    @Test
    fun logisticsTagServerValuesMatchSharedTaxonomy() {
        assertEquals(
            DiscoveryTagTaxonomies.logisticsTags,
            LogisticsTag.entries.map { it.serverValue }.toSet(),
        )
    }

    @Test
    fun intentServerValuesMatchSharedTaxonomy() {
        assertEquals(
            DiscoveryTagTaxonomies.intents,
            Intent.entries.map { it.serverValue }.toSet(),
        )
    }

    @Test
    fun filterDiscoveryStylesAreValidProfileValues() {
        // The grid/swipe filter omits `ask_in_chat` (a profile-only preference), so the filter
        // enum is a subset — every filterable style must still be a valid profile discovery_style.
        val filterStyles = DiscoveryStyle.entries.map { it.serverValue }.toSet()
        assertTrue(
            DiscoveryTagTaxonomies.discoveryStyles.containsAll(filterStyles),
            "Filter styles $filterStyles must be valid profile values ${DiscoveryTagTaxonomies.discoveryStyles}",
        )
    }
}
