package com.pynapli.app.grid

import com.pynapli.shared.network.dto.GridCardDto
import com.pynapli.shared.network.dto.GridQueryRequestDto
import com.pynapli.shared.network.dto.LinkedPartnerMiniDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Plan G2-3 on the client: partner filters, "No single men", the Who interplay and the mini caption. */
class PartnerFiltersClientTest {
    private val partnerFilters =
        GridFilters(
            partnerGenders = setOf("woman"),
            partnerMinimumAge = 25,
            partnerMaximumAge = 40,
            partnerOrientations = setOf("bisexual"),
            partnerVerified = true,
            noSingleMen = true,
        )

    @Test
    fun requestCarriesThemAndASavedPresetOrStoredFiltersRestoreThem() {
        val request = partnerFilters.toGridQueryRequest(cursor = null)
        assertEquals(listOf("woman"), request.partnerGenders)
        assertEquals(25, request.partnerMinimumAge)
        assertEquals(40, request.partnerMaximumAge)
        assertEquals(listOf("bisexual"), request.partnerOrientations)
        assertTrue(request.partnerVerifiedOnly)
        assertTrue(request.excludeSingleMen)
        assertEquals(partnerFilters, GridFilters.fromGridQueryRequest(request))
        // The device store writes the same shape.
        assertEquals(partnerFilters, decodeGridFilters(encodeGridFilters("me", partnerFilters), "me"))
        // An older payload without the fields restores none of them.
        val old = GridFilters.fromGridQueryRequest(GridQueryRequestDto(linkedOnly = true))
        assertFalse(old.hasPartnerFilters)
        assertFalse(old.noSingleMen)
    }

    @Test
    fun partnerFiltersImplyLinkedSoSinglesAloneDropsThem() {
        val singles = partnerFilters.copy(singles = true)
        assertFalse(singles.partnerFiltersApply)
        val request = singles.toGridQueryRequest(cursor = null)
        assertTrue(request.partnerGenders.isEmpty())
        assertNull(request.partnerMinimumAge)
        assertTrue(request.partnerOrientations.isEmpty())
        assertFalse(request.partnerVerifiedOnly)
        // "No single men" is not a partner filter: it still travels.
        assertTrue(request.excludeSingleMen)
        assertFalse(singles.withPartnerFiltersReconciled().hasPartnerFilters)
        // Singles + Linked is everyone, and Linked alone, both let them apply.
        assertTrue(partnerFilters.copy(singles = true, linked = true).partnerFiltersApply)
        assertTrue(partnerFilters.copy(linked = true).partnerFiltersApply)
        assertTrue(partnerFilters.partnerFiltersApply)
    }

    @Test
    fun activeCountCountsEachPartnerGroupOnceAndOnlyWhenItApplies() {
        // gender, age, orientation, verified, No single men.
        assertEquals(5, partnerFilters.activeCount)
        // Singles itself (1) + No single men (1); the dropped partner filters are not counted.
        assertEquals(2, partnerFilters.copy(singles = true).activeCount)
        assertTrue(GridFilters(noSingleMen = true).isAnyActive)
    }

    @Test
    fun singlesShortcutsSetSinglesAndGenderAndClearBack() {
        val applied = SinglesShortcut.SingleWomen.applied(partnerFilters)
        assertTrue(applied.singles)
        assertFalse(applied.linked)
        assertEquals(setOf("woman"), applied.genders)
        assertFalse(applied.hasPartnerFilters, "Singles rules the partner filters out")
        assertTrue(SinglesShortcut.SingleWomen.isActive(applied))
        assertFalse(SinglesShortcut.SingleMen.isActive(applied))
        val cleared = SinglesShortcut.SingleWomen.cleared(applied)
        assertFalse(cleared.singles)
        assertTrue(cleared.genders.isEmpty())
    }

    @Test
    fun miniCaptionShowsThePartnersOwnAgeAndGenderMarkOnly() {
        assertEquals("34 · W", partnerMiniCaption(GridLinkedPartnerUi("p", age = 34, gender = "woman")))
        assertEquals("M", partnerMiniCaption(GridLinkedPartnerUi("p", gender = "man")))
        assertEquals("41", partnerMiniCaption(GridLinkedPartnerUi("p", age = 41)))
        // Self-describe and unknown keys get no mark.
        assertEquals("29", partnerMiniCaption(GridLinkedPartnerUi("p", age = 29, gender = "self_describe")))
        assertNull(partnerMiniCaption(GridLinkedPartnerUi("p", gender = "self_describe")))
        assertNull(partnerMiniCaption(GridLinkedPartnerUi("p")))
        assertNull(partnerMiniCaption(null))
        assertEquals("Linked partner, 34, woman", partnerMiniDescription(GridLinkedPartnerUi("p", age = 34, gender = "woman")))
        assertEquals("Linked partner", partnerMiniDescription(GridLinkedPartnerUi("p", gender = "self_describe")))
    }

    @Test
    fun captionOnlyWhereTheServerShowsAMiniAndNeverOnALockedCard() {
        val withPartner =
            card(partner = LinkedPartnerMiniDto(userId = "p", displayName = "P", age = 30, gender = "woman")).toGridProfileCardUi(0)
        assertEquals("30 · W", partnerMiniCaption(withPartner.linkedPartner))
        // No mini from the server (hidden partner): no caption, nothing to infer.
        assertNull(partnerMiniCaption(card(partner = null).toGridProfileCardUi(0).linkedPartner))
        val locked =
            card(partner = LinkedPartnerMiniDto(userId = "p", age = 30, gender = "woman"), locked = true).toGridProfileCardUi(0)
        assertNull(locked.linkedPartner)
    }

    private fun card(
        partner: LinkedPartnerMiniDto?,
        locked: Boolean = false,
    ) = GridCardDto(
        userId = if (locked) "locked_abc" else "u-1",
        displayName = if (locked) "" else "Sam",
        online = false,
        linkedPartner = partner,
        rankToken = "t",
        locked = locked,
    )
}
