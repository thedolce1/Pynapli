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
            DiscoveryTagTaxonomies.lifestyleTags,
            LifestyleTag.entries.map { it.serverValue }.toSet(),
        )
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
