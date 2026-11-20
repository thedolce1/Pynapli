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

/** The menu item, the sheet's states and the holder's calls follow the server's offers and nothing else. */
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
                        metInPersonOffers = offers,
                    ),
                messages = emptyList(),
            ),
        metInPersonSheet = sheet,
    )

    @Test
    fun theMenuItemShowsOnlyWhenTheServerOffersIt() {
        assertEquals(listOf("We've met in person"), stateWith(listOf(available)).metInPersonMenuItems().map { it.label })
        assertTrue(stateWith(null).metInPersonMenuItems().isEmpty(), "older server")
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
    fun theConfirmSheetWordsThePromiseAndOffersThePartnerOnlyWhenAllowed() {
        val ui = assertNotNull(stateWith(listOf(available), MetInPersonSheetState("target")).metInPersonSheetUi())
        assertEquals(
            "Have you met Maya in person, and were they who their profile says? Only you'll know you answered. " +
                "They won't be told, and nobody sees who confirmed.",
            ui.body,
        )
        assertTrue(ui.showConfirm)
        assertFalse(ui.showTakeBack)
        assertNull(ui.partnerTickLabel)

        val withPartner = available.copy(partner = MetInPersonPartnerDto("riley", "Riley"))
        val ticked = assertNotNull(stateWith(listOf(withPartner), MetInPersonSheetState("target", includePartner = true)).metInPersonSheetUi())
        assertEquals("And Riley", ticked.partnerTickLabel)
        assertTrue(ticked.includePartner)
    }

    @Test
    fun aGivenConfirmationOffersTakeItBackWithinThirtyDays() {
        val given = available.copy(given = true, canConfirm = false, canWithdraw = true)
        val ui = assertNotNull(stateWith(listOf(given), MetInPersonSheetState("target")).metInPersonSheetUi())
        assertFalse(ui.showConfirm)
        assertTrue(ui.showTakeBack)
        assertTrue(ui.body.contains("take it back"))
        assertTrue(listOf(ui.title, ui.body).none { it.contains("count", ignoreCase = true) })
    }

    @Test
    fun noSheetWithoutAMatchingOffer() {
        assertNull(stateWith(listOf(available), MetInPersonSheetState("someone-else")).metInPersonSheetUi())
        assertNull(stateWith(null, MetInPersonSheetState("target")).metInPersonSheetUi())
    }

    private class Repo(
        var offers: List<MetInPersonOfferDto>,
        var fail: Boolean = false,
    ) : ConversationRepository by SampleConversationRepository() {
        val confirmations = mutableListOf<Pair<String, Boolean>>()
        val withdrawals = mutableListOf<String>()

        override suspend fun markConversationRead(conversationId: String): AppResult<ConversationDto> =
            when (val base = SampleConversationRepository().markConversationRead(conversationId)) {
                is AppResult.Success -> AppResult.Success(base.value.copy(metInPersonOffers = offers))
                is AppResult.Failure -> base
            }

        override suspend fun confirmMetInPerson(
            conversationId: String,
            subjectUserId: String,
            includePartner: Boolean,
        ): AppResult<MetInPersonOfferDto> {
            if (fail) return AppResult.Failure(AppError(code = "MET_IN_PERSON_NOT_AVAILABLE", message = "no"))
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
            val updated = offers.first { it.subjectUserId == subjectUserId }.copy(given = false, canConfirm = true, canWithdraw = false)
            offers = offers.map { if (it.subjectUserId == subjectUserId) updated else it }
            return AppResult.Success(updated)
        }
    }

    @Test
    fun confirmingSendsThePartnerTickAndTakesTheServersAnswer() =
        runTest {
            val repo = Repo(listOf(available.copy(partner = MetInPersonPartnerDto("riley", "Riley"))))
            val holder = ConversationStateHolder(repo)
            holder.openConversationById("c1", viewerUserId = "viewer")
            assertEquals(1, holder.state.value.metInPersonMenuItems().size)

            holder.openMetInPerson("target")
            holder.setMetInPersonIncludePartner(true)
            holder.confirmMetInPerson()

            assertEquals(listOf("target" to true), repo.confirmations)
            assertNull(holder.state.value.metInPersonSheet)
            val offer = holder.state.value.detail?.conversation?.metInPersonOffers?.single()
            assertTrue(offer?.given == true && offer.canWithdraw)

            holder.openMetInPerson("target")
            assertTrue(assertNotNull(holder.state.value.metInPersonSheetUi()).showTakeBack)
            holder.takeBackMetInPerson()
            assertEquals(listOf("target"), repo.withdrawals)
        }

    @Test
    fun aFailureKeepsTheSheetOpenWithANeutralMessage() =
        runTest {
            val repo = Repo(listOf(available), fail = true)
            val holder = ConversationStateHolder(repo)
            holder.openConversationById("c1", viewerUserId = "viewer")
            holder.openMetInPerson("target")
            holder.confirmMetInPerson()

            val ui = assertNotNull(holder.state.value.metInPersonSheetUi())
            assertFalse(ui.isSubmitting)
            assertEquals("That didn't go through. Try again in a moment.", ui.errorMessage)
        }
}
