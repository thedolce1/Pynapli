package com.pynapli.app.conversation

import com.pynapli.shared.network.PynapliJson
import com.pynapli.shared.network.dto.ConversationBringPartnerDto
import com.pynapli.shared.network.dto.ConversationDetailDto
import com.pynapli.shared.network.dto.ConversationDto
import com.pynapli.shared.network.dto.ConversationParticipantDto
import com.pynapli.shared.network.dto.ConversationParticipantPartnerDto
import com.pynapli.shared.network.dto.ConversationParticipantStatus
import com.pynapli.shared.network.dto.ConversationPartnerOffersDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The chrome words the server's "Bring your partner" offer and nothing else; no field, no offer. */
class ConversationPartnerOfferTest {
    @Test
    fun theOwnPartnerOfferAndAVisiblePartnerNoteAreWordedFromTheServer() {
        val state =
            stateFor(
                ConversationPartnerOffersDto(
                    bringPartner = ConversationBringPartnerDto(partnerUserId = "jo", partnerDisplayName = "Jo"),
                    participantPartners =
                        listOf(ConversationParticipantPartnerDto(participantUserId = "sam", partnerDisplayName = "Riley")),
                ),
            )

        val offer = state.partnerOfferUi()

        assertEquals("Jo", offer?.bringPartnerName)
        assertEquals(
            listOf("Sam is linked with Riley. Ask Sam if Riley would like to join."),
            offer?.participantPartnerNotes,
        )
    }

    @Test
    fun noOfferWhenTheServerSendsNone() {
        assertNull(stateFor(offers = null).partnerOfferUi())
        assertNull(stateFor(ConversationPartnerOffersDto()).partnerOfferUi())
    }

    @Test
    fun aNoteForSomeoneNotInTheConversationIsDropped() {
        val state =
            stateFor(
                ConversationPartnerOffersDto(
                    participantPartners =
                        listOf(ConversationParticipantPartnerDto(participantUserId = "gone", partnerDisplayName = "Riley")),
                ),
            )

        assertNull(state.partnerOfferUi())
    }

    @Test
    fun anInvitedViewerOrALeavingViewerIsNotOffered() {
        val offers =
            ConversationPartnerOffersDto(
                bringPartner = ConversationBringPartnerDto(partnerUserId = "jo", partnerDisplayName = "Jo"),
            )

        assertNull(stateFor(offers, viewerStatus = ConversationParticipantStatus.INVITED).partnerOfferUi())
        assertNull(stateFor(offers).copy(isLeaving = true).partnerOfferUi())
    }

    @Test
    fun anOlderServerResponseWithoutTheFieldRendersAsBefore() {
        val json =
            """
            {"conversationId":"c1","kind":"direct","origin":"swipe",
             "participants":[{"userId":"me","displayName":"Alex"},{"userId":"sam","displayName":"Sam"}],
             "unreadCount":0,"updatedAt":"2026-11-18T10:00:00Z"}
            """.trimIndent()

        val conversation = PynapliJson.decodeFromString(ConversationDto.serializer(), json)

        assertNull(conversation.partnerOffers)
        val state =
            ConversationUiState(
                viewerUserId = "me",
                detail = ConversationDetailDto(conversation = conversation, messages = emptyList()),
            )
        assertNull(state.partnerOfferUi())
    }

    @Test
    fun aNewerServerResponseDecodesTheOffer() {
        val json =
            """
            {"conversationId":"c1","kind":"direct","origin":"swipe","participants":[],
             "unreadCount":0,"updatedAt":"2026-11-18T10:00:00Z",
             "partnerOffers":{"bringPartner":{"partnerUserId":"jo","partnerDisplayName":"Jo"}}}
            """.trimIndent()

        val conversation = PynapliJson.decodeFromString(ConversationDto.serializer(), json)

        assertEquals("Jo", conversation.partnerOffers?.bringPartner?.partnerDisplayName)
        assertEquals(emptyList<ConversationParticipantPartnerDto>(), conversation.partnerOffers?.participantPartners)
    }

    @Test
    fun loadingOlderMessagesKeepsTheOfferFromTheReadMark() {
        val offers =
            ConversationPartnerOffersDto(
                bringPartner = ConversationBringPartnerDto(partnerUserId = "jo", partnerDisplayName = "Jo"),
            )
        val open = requireNotNull(stateFor(offers).detail)
        val olderPage = requireNotNull(stateFor(offers = null).detail)

        assertEquals(offers, open.withPrependedPage(olderPage).conversation.partnerOffers)
    }

    private fun stateFor(
        offers: ConversationPartnerOffersDto?,
        viewerStatus: String = ConversationParticipantStatus.ACTIVE,
    ): ConversationUiState =
        ConversationUiState(
            viewerUserId = "me",
            detail =
                ConversationDetailDto(
                    conversation =
                        ConversationDto(
                            conversationId = "c1",
                            kind = "direct",
                            origin = "swipe",
                            participants =
                                listOf(
                                    ConversationParticipantDto(userId = "me", displayName = "Alex", status = viewerStatus),
                                    ConversationParticipantDto(userId = "sam", displayName = "Sam"),
                                ),
                            unreadCount = 0,
                            updatedAt = "2026-11-18T10:00:00Z",
                            partnerOffers = offers,
                        ),
                    messages = emptyList(),
                ),
        )
}
