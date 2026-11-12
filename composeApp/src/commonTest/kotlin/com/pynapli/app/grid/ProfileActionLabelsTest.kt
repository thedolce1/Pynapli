package com.pynapli.app.grid

import com.pynapli.app.messages.originLabel
import com.pynapli.app.profilelink.ProfileLinksUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProfileActionLabelsTest {
    @Test
    fun invitingAStrangerToLinkLivesInTheMoreMenuNotTheMainButtons() {
        val action = ProfileLinksUiState().profileLinkActionFor(targetUserId = "them", viewerUserId = "me")

        assertEquals("Invite to link as partners", action?.label)
        assertTrue(action!!.offeredInMenu)
    }

    @Test
    fun aSendingInviteStaysVisibleAsAStatus() {
        val action =
            ProfileLinksUiState(submittingTargetUserIds = setOf("them"))
                .profileLinkActionFor(targetUserId = "them", viewerUserId = "me")

        assertEquals("Sending invite", action?.label)
        assertFalse(action!!.offeredInMenu)
    }

    @Test
    fun discoveryStyleReadsAsAPhrase() {
        assertEquals("Open to both", discoveryTagLabel("both"))
        assertEquals("Ask in chat", discoveryTagLabel("ask_in_chat"))
    }

    @Test
    fun onlyNotableConversationOriginsGetAChip() {
        assertEquals("Match", "swipe".originLabel())
        assertEquals("Intro", "intro_note".originLabel())
        assertNull("grid".originLabel())
    }
}
