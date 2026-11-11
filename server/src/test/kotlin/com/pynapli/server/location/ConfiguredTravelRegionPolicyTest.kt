package com.pynapli.server.location

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class ConfiguredTravelRegionPolicyTest {
    private fun ref(code: String?) = TravelRegionRef("City", null, null, code)

    @Test
    fun blankMeansNoRules() {
        assertSame(EmptyTravelRegionPolicy, ConfiguredTravelRegionPolicy.parse(null))
        assertSame(EmptyTravelRegionPolicy, ConfiguredTravelRegionPolicy.parse("  , "))
    }

    @Test
    fun rulesApplyByResolvedCountryCodeCaseInsensitively() {
        val policy = ConfiguredTravelRegionPolicy.parse("ae=blocked, QA=age_verified")

        assertEquals(TravelRegionRequirement.Blocked, policy.requirementFor(ref("AE")))
        assertEquals(TravelRegionRequirement.AgeVerified, policy.requirementFor(ref("qa")))
        assertEquals(TravelRegionRequirement.None, policy.requirementFor(ref("US")))
        // Unresolved country (open water): nothing extra.
        assertEquals(TravelRegionRequirement.None, policy.requirementFor(ref(null)))
    }

    @Test
    fun aMalformedEntryFailsStartup() {
        assertFailsWith<IllegalArgumentException> { ConfiguredTravelRegionPolicy.parse("AE") }
        assertFailsWith<IllegalArgumentException> { ConfiguredTravelRegionPolicy.parse("UAE=blocked") }
        assertFailsWith<IllegalArgumentException> { ConfiguredTravelRegionPolicy.parse("AE=forbidden") }
    }
}
