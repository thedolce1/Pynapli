package com.pynapli.app.psync

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.dto.CurrentPsyncSessionDto
import com.pynapli.shared.network.dto.PsyncActionResponseDto
import com.pynapli.shared.network.dto.PsyncDeckCardDto
import com.pynapli.shared.network.dto.PsyncDeckResponseDto
import com.pynapli.shared.network.dto.PsyncMatchDto
import com.pynapli.shared.network.dto.PsyncParticipantDto
import com.pynapli.shared.network.dto.PsyncRecapDto
import com.pynapli.shared.network.dto.PsyncSessionDto
import com.pynapli.shared.network.dto.SwipeCardDto
import com.pynapli.shared.network.dto.SwipeDeckRequestDto
import kotlin.test.Test

private const val VIEWER = "viewer-1"
private const val PARTNER = "partner-1"

private fun session(status: String): PsyncSessionDto =
    PsyncSessionDto(
        id = "s-1",
        partnerLinkId = "link-1",
        initiatorUserId = VIEWER,
        status = status,
        filters = SwipeDeckRequestDto(),
        deckSize = 2,
        createdAt = "2026-06-04T12:00:00Z",
        startedAt = null,
        expiresAt = "2026-06-05T12:00:00Z",
        participants =
            listOf(
                PsyncParticipantDto(VIEWER, "Viewer", "initiator", "accepted", true),
                PsyncParticipantDto(PARTNER, "Pat", "partner", "accepted", true),
            ),
    )

private fun card(token: String): PsyncDeckCardDto =
    PsyncDeckCardDto(
        card = SwipeCardDto(userId = "t-$token", displayName = "Target $token", rankToken = "r"),
        cardToken = token,
        viewerAction = null,
    )

private class StaticPsyncRepository(
    private val current: PsyncSessionDto?,
    private val deck: List<PsyncDeckCardDto> = emptyList(),
    private val recap: PsyncRecapDto? = null,
) : PsyncRepository {
    override suspend fun loadCurrentSession() = AppResult.Success(CurrentPsyncSessionDto(session = current))

    override suspend fun createSession(deckSize: Int) = AppResult.Success(current ?: session("invited"))

    override suspend fun accept(sessionId: String) = AppResult.Success(current ?: session("ready"))

    override suspend fun deny(sessionId: String) = AppResult.Success(current ?: session("cancelled"))

    override suspend fun leave(sessionId: String) = AppResult.Success(current ?: session("cancelled"))

    override suspend fun start(sessionId: String) = AppResult.Success(current ?: session("active"))

    override suspend fun loadDeck(sessionId: String) = AppResult.Success(PsyncDeckResponseDto(sessionId = sessionId, deckSize = deck.size, cards = deck))

    override suspend fun submitAction(
        sessionId: String,
        cardToken: String,
        action: String,
    ) = AppResult.Success(PsyncActionResponseDto(recorded = true, actedCount = 1, deckSize = deck.size))

    override suspend fun pause(sessionId: String) = AppResult.Success(current ?: session("paused"))

    override suspend fun resume(sessionId: String) = AppResult.Success(current ?: session("active"))

    override suspend fun complete(sessionId: String) = AppResult.Success(recap!!)

    override suspend fun loadRecap(sessionId: String) = AppResult.Success(recap!!)

    override suspend fun nudge(sessionId: String) = AppResult.Success(Unit)
}

class PsyncSessionScreenTest {
    private fun holder(repo: PsyncRepository) = PsyncStateHolder(repo, VIEWER)

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun idleShowsStartButton() =
        runComposeUiTest {
            setContent {
                PsyncSessionScreen(stateHolder = holder(StaticPsyncRepository(current = null)), onBack = {})
            }
            waitForIdle()
            onNodeWithTag(PsyncSessionTags.START_PSYNC_BUTTON).assertIsDisplayed()
        }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun activeSessionShowsDeckCardAndSwipeActions() =
        runComposeUiTest {
            val repo = StaticPsyncRepository(current = session("active"), deck = listOf(card("a"), card("b")))
            setContent { PsyncSessionScreen(stateHolder = holder(repo), onBack = {}) }
            waitForIdle()

            onNodeWithTag(PsyncSessionTags.CARD_NAME).assertIsDisplayed()
            onNodeWithTag(PsyncSessionTags.PROGRESS).assertIsDisplayed()
            // Swiping the first card locally advances to the second (still a card on screen).
            onNodeWithTag(PsyncSessionTags.LIKE_BUTTON).performClick()
            waitForIdle()
            onNodeWithTag(PsyncSessionTags.CARD_NAME).assertIsDisplayed()
        }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun completedSessionShowsRecap() =
        runComposeUiTest {
            val recap =
                PsyncRecapDto(
                    sessionId = "s-1",
                    status = "completed",
                    profilesReviewed = 2,
                    bothLikedCount = 1,
                    togetherMatchCount = 1,
                    soloMatchCount = 0,
                    filtersSummary = "25km",
                    togetherMatches = listOf(PsyncMatchDto(targetUserId = "t-a", displayName = "Target a", kind = "together")),
                )
            val repo = StaticPsyncRepository(current = session("completed"), recap = recap)
            setContent { PsyncSessionScreen(stateHolder = holder(repo), onBack = {}) }
            waitForIdle()

            onNodeWithTag(PsyncSessionTags.RECAP).assertIsDisplayed()
        }
}
