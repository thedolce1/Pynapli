package com.pynapli.server.profile

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContactAndSolicitationBioScannerTest {
    private fun rejects(bio: String) = ContactAndSolicitationBioScanner.rejects(bio)

    @Test
    fun ordinaryLifestyleBiosPass() {
        listOf(
            "Fun, discreet couple in our 30s. Together since 2015. Love travel, wine and good company.",
            "She's 34, he's 36. New to this, take it slow. Drinks first, always.",
            "5'10, fit, sapiosexual. Open to meeting couples and singles. No pressure.",
            "Hosting most weekends in the city. Ask us about our favourite spots.",
            "We like dancing, hot tubs and the occasional roseé.",
        ).forEach { bio -> assertFalse(rejects(bio), bio) }
    }

    @Test
    fun linksContactDetailsAndOtherAppsAreRejected() {
        listOf(
            "Find us at www.ourcouple.com",
            "check out ourblog.net for more",
            "ourpage dot com",
            "email me: fun.couple@gmail.com",
            "fun.couple (at) gmail dot com",
            "Text 555 123 4567",
            "call +1 (415) 555-0199",
            "add me on telegram",
            "snap: hotcouple22",
            "IG @thehotcouple",
            "Subscribe to my OnlyFans",
        ).forEach { bio -> assertTrue(rejects(bio), bio) }
    }

    @Test
    fun paymentRequestsAreRejected() {
        listOf(
            "$200/hr, no exceptions",
            "Generous gentlemen only, my rates are in DMs",
            "Cashapp me for pics",
            "venmo first",
            "send to \$bigspender",
            "Looking for a sugar daddy",
        ).forEach { bio -> assertTrue(rejects(bio), bio) }
    }
}
