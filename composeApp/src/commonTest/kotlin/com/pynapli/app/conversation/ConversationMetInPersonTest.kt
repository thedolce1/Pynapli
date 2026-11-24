package com.pynapli.app.conversation

import com.pynapli.shared.core.result.AppError
import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.dto.ConversationDetailDto
import com.pynapli.shared.network.dto.ConversationDto
import com.pynapli.shared.network.dto.ConversationParticipantDto
import com.pynapli.shared.network.dto.MetInPersonOfferDto
import com.pynapli.shared.network.dto.MetInPersonPartnerDto
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The menu, the sheet's states and the holder's calls follow the server's offers and nothing else. */
class ConversationMetInPersonTest {
    private val available = MetInPersonOfferDto(subjectUserId = "target", subjectDisplayName = "Maya", canConfirm = true)

    private fun stateWith(
        offers: List<MetInPersonOfferDto>?,
        sheet: MetInPersonSheetState? = null,
    ) = ConversationUiState(
        viewerUserId = "viewer",
        detail =
            ConversationDetailDto(
                conversation =
                    ConversationDto(
                        conversationId = "c1",
                        kind = "direct",
                        origin = "grid",
                        participants = listOf(ConversationParticipantDto("viewer", "You"), ConversationParticipantDto("target", "Maya")),
                        unreadCount = 0,
                        updatedAt = "2026-11-18T12:00:00Z",
                    ),
                messages = emptyList(),
            ),
        metInPersonSheet = sheet,
        metInPersonOffers = offers,
    )

    @Test
    fun theMenuItemShowsOnlyWhenTheServerOffersIt() {
        assertEquals(listOf("We've met in person"), stateWith(listOf(available)).metInPersonMenuItems().map { it.label })
        assertTrue(stateWith(null).metInPersonMenuItems().isEmpty(), "not fetched yet")
        assertTrue(stateWith(emptyList()).metInPersonMenuItems().isEmpty())
        assertTrue(stateWith(listOf(available.copy(canConfirm = false))).metInPersonMenuItems().isEmpty())
    }

    @Test
    fun aGroupNamesWhoEachItemIsAbout() {
        val offers = listOf(available, MetInPersonOfferDto("riley", "Riley", canConfirm = true))
        assertEquals(
            listOf("We've met Maya in person", "We've met Riley in person"),
            stateWith(offers).metInPersonMenuItems().map { it.label },
        )
    }

    @Test
    fun theConfirmSheetSaysWhoCanSeeAndOffersThePartnerOnlyWhenAllowed() {
        val ui = assertNotNull(stateWith(listOf(available), MetInPersonSheetState("target")).metInPersonSheetUi())
        assertEquals("Have you met Maya in person? They won't be told. Pynapli's safety team can see confirmations if needed.", ui.body)
        assertTrue(ui.showConfirm)
        assertFalse(ui.showTakeBack)
        assertNull(ui.partnerTickLabel)

        val withPartner = available.copy(partner = MetInPersonPartnerDto("riley", "Riley"))
        val ticked = assertNotNull(stateWith(listOf(withPartner), MetInPersonSheetState("target", includePartner = true)).metInPersonSheetUi())
        assertEquals("And Riley", ticked.partnerTickLabel)
        assertTrue(ticked.includePartner)
    }

    @Test
    fun aGivenConfirmationOffersTakeItBack() {
        val given = available.copy(given = true, canConfirm = false, canWithdraw = true)
        val ui = assertNotNull(stateWith(listOf(given), MetInPersonSheetState("target")).metInPersonSheetUi())
        assertFalse(ui.showConfirm)
        assertTrue(ui.showTakeBack)
        assertTrue(listOf(ui.title, ui.body).none { it.contains("count", ignoreCase = true) })
    }

    @Test
    fun noSheetWithoutAMatchingOffer() {
        assertNull(stateWith(listOf(available), MetInPersonSheetState("someone-else")).metInPersonSheetUi())
        assertNull(stateWith(null, MetInPersonSheetState("target")).metInPersonSheetUi())
    }

    private class Repo(
        var offers: List<MetInPersonOfferDto>,
        var failOffers: Boolean = false,
        var failConfirm: Boolean = false,
    ) : ConversationRepository by SampleConversationRepository() {
        val confirmations = mutableListOf<Pair<String, Boolean>>()
        val withdrawals = mutableListOf<String>()
        var offerCalls = 0

        override suspend fun metInPersonOffers(conversationId: String): AppResult<List<MetInPersonOfferDto>> {
            offerCalls++
            return if (failOffers) AppResult.Failure(AppError(code = "SERVER_ERROR", message = "boom")) else AppResult.Success(offers)
        }

        override suspend fun confirmMetInPerson(
            conversationId: String,
            subjectUserId: String,
            includePartner: Boolean,
        ): AppResult<MetInPersonOfferDto> {
            if (failConfirm) return AppResult.Failure(AppError(code = "MET_IN_PERSON_NOT_AVAILABLE", message = "no"))
            confirmations += subjectUserId to includePartner
            val updated = offers.first { it.subjectUserId == subjectUserId }.copy(given = true, canConfirm = false, canWithdraw = true, partner = null)
            offers = offers.map { if (it.subjectUserId == subjectUserId) updated else it }
            return AppResult.Success(updated)
        }

        override suspend fun withdrawMetInPerson(
            conversationId: String,
            subjectUserId: String,
        ): AppResult<MetInPersonOfferDto> {
            withdrawals += subjectUserId
            val updated = offers.first { it.subjectUserId == subjectUserId }.copy(given = false, canConfirm = false, canWithdraw = false)
            offers = offers.map { if (it.subjectUserId == subjectUserId) updated else it }
            return AppResult.Success(updated)
        }
    }

    @Test
    fun openingAConversationNeverAsksForOffersAndAFailureOnlyEmptiesTheMenu() =
        runTest {
            val repo = Repo(listOf(available), failOffers = true)
            val holder = ConversationStateHolder(repo)
            holder.openConversationById("c1", viewerUserId = "viewer")
            assertEquals(0, repo.offerCalls, "not on open")
            assertNotNull(holder.state.value.detail)

            holder.loadMetInPersonOffers()
            assertEquals(1, repo.offerCalls)
            assertNotNull(holder.state.value.detail, "the conversation is untouched")
            assertNull(holder.state.value.errorMessage)
            assertTrue(
                holder.state.value
                    .metInPersonMenuItems()
                    .isEmpty(),
            )
            assertFalse(holder.state.value.isLoadingMetInPersonOffers)
        }

    @Test
    fun confirmingSendsThePartnerTickAndTakesTheServersAnswer() =
        runTest {
            val repo = Repo(listOf(available.copy(partner = MetInPersonPartnerDto("riley", "Riley"))))
            val holder = ConversationStateHolder(repo)
            holder.openConversationById("c1", viewerUserId = "viewer")
            holder.loadMetInPersonOffers()
            assertEquals(
                1,
                holder.state.value
                    .metInPersonMenuItems()
                    .size,
            )

            holder.openMetInPerson("target")
            holder.setMetInPersonIncludePartner(true)
            holder.confirmMetInPerson()

            assertEquals(listOf("target" to true), repo.confirmations)
            assertNull(holder.state.value.metInPersonSheet)
            val offer =
                holder.state.value.metInPersonOffers
                    ?.single()
            assertTrue(offer?.given == true && offer.canWithdraw)

            holder.openMetInPerson("target")
            assertTrue(assertNotNull(holder.state.value.metInPersonSheetUi()).showTakeBack)
            holder.takeBackMetInPerson()
            assertEquals(listOf("target"), repo.withdrawals)
        }

    @Test
    fun aFailedConfirmKeepsTheSheetOpenWithANeutralMessage() =
        runTest {
            val repo = Repo(listOf(available), failConfirm = true)
            val holder = ConversationStateHolder(repo)
            holder.openConversationById("c1", viewerUserId = "viewer")
            holder.loadMetInPersonOffers()
            holder.openMetInPerson("target")
            holder.confirmMetInPerson()

            val ui = assertNotNull(holder.state.value.metInPersonSheetUi())
            assertFalse(ui.isSubmitting)
            assertEquals("That didn't go through. Try again in a moment.", ui.errorMessage)
        }
}
