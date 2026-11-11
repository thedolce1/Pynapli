package com.pynapli.app.grid

import kotlin.test.Test
import kotlin.test.assertEquals

class DiscoveryTagLabelsTest {
    @Test
    fun everyTaxonomyKeyGetsItsLabel() {
        assertEquals("Public meet first", discoveryTagLabel("public_meet_first"))
        assertEquals("Vanilla/social", discoveryTagLabel("vanilla_social"))
        assertEquals("Chat first", discoveryTagLabel("chat_first"))
        assertEquals("Open to both", discoveryTagLabel("both"))
    }

    @Test
    fun anUnknownKeyIsTidiedRatherThanShownRaw() {
        assertEquals("Brand new thing", discoveryTagLabel("brand_new_thing"))
    }
}
