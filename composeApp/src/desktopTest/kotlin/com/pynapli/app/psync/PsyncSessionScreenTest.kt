package com.pynapli.app.psync

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.pynapli.app.swipe.SwipeTestTags
import com.pynapli.shared.core.result.AppError
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
    var current: PsyncSessionDto?,
    private val deck: List<PsyncDeckCardDto> = emptyList(),
    private val recap: PsyncRecapDto? = null,
) : PsyncRepository {
    // Like the server, only a live session is "current"; a finished one is read by id.
    override suspend fun loadCurrentSession() = AppResult.Success(CurrentPsyncSessionDto(session = current?.takeIf { it.status !in setOf("completed", "expired", "cancelled") }))

    override suspend fun loadSession(sessionId: String) = AppResult.Success(current ?: session("cancelled"))

    var createdWith: Pair<Int, SwipeDeckRequestDto>? = null

    override suspend fun createSession(
        deckSize: Int,
        filters: SwipeDeckRequestDto,
    ): AppResult<PsyncSessionDto> {
        createdWith = deckSize to filters
        return AppResult.Success(current ?: session("invited"))
    }

    override suspend fun suggestFilters(
        sessionId: String,
        filters: SwipeDeckRequestDto,
    ) = AppResult.Success(current ?: session("configuring"))

    override suspend fun accept(sessionId: String) = AppResult.Success(current ?: session("ready"))

    override suspend fun deny(sessionId: String) = AppResult.Success(current ?: session("cancelled"))

    var leaveCalls = 0

    override suspend fun leave(sessionId: String): AppResult<PsyncSessionDto> {
        leaveCalls += 1
        return AppResult.Success(current ?: session("cancelled"))
    }

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
    fun setupInvitesWithTheChosenDeckSize() =
        runComposeUiTest {
            val repo = StaticPsyncRepository(current = null)
            setContent { PsyncSessionScreen(stateHolder = holder(repo), onBack = {}) }
            waitForIdle()

            onNodeWithTag(PsyncSessionTags.SETUP_SUMMARY).assertIsDisplayed()
            onNodeWithTag(PsyncSessionTags.deckSizeChip(10)).performClick()
            onNodeWithTag(PsyncSessionTags.START_PSYNC_BUTTON).performClick()
            waitForIdle()

            kotlin.test.assertEquals(10, repo.createdWith?.first)
        }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun theInviteeSeesTheSetupAndCanAcceptSuggestOrDecline() =
        runComposeUiTest {
            val invited =
                session("invited").copy(
                    initiatorUserId = PARTNER,
                    deckSize = 25,
                    filters = SwipeDeckRequestDto(minimumAge = 30, maximumAge = 45, verifiedOnly = true),
                    participants =
                        listOf(
                            PsyncParticipantDto(VIEWER, "Viewer", "partner", "pending", false),
                            PsyncParticipantDto(PARTNER, "Pat", "initiator", "accepted", true),
                        ),
                )
            setContent { PsyncSessionScreen(stateHolder = holder(StaticPsyncRepository(current = invited)), onBack = {}) }
            waitForIdle()

            onNodeWithText("Pat invited you to Psync").assertIsDisplayed()
            onNodeWithText("25 profiles").assertIsDisplayed()
            onNodeWithText("Ages 30 to 45").assertIsDisplayed()
            onNodeWithText("Verified only").assertIsDisplayed()
            onNodeWithTag(PsyncSessionTags.ACCEPT_BUTTON).assertIsDisplayed()
            onNodeWithTag(PsyncSessionTags.SUGGEST_CHANGES_BUTTON).assertIsDisplayed()
            onNodeWithTag(PsyncSessionTags.DECLINE_BUTTON).assertIsDisplayed()
        }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun activeSessionShowsDeckCardAndSwipeActions() =
        runComposeUiTest {
            val repo = StaticPsyncRepository(current = session("active"), deck = listOf(card("a"), card("b")))
            setContent { PsyncSessionScreen(stateHolder = holder(repo), onBack = {}) }
            waitForIdle()

            // The deck is the shared Swipe card stack, not a placeholder.
            onNodeWithTag(SwipeTestTags.TOP_CARD).assertIsDisplayed()
            onNodeWithText("0 of 2 reviewed").assertIsDisplayed()
            // Liking the first card flies it out and advances to the second.
            onNodeWithTag(PsyncSessionTags.LIKE_BUTTON).performClick()
            waitForIdle()
            onNodeWithText("1 of 2 reviewed").assertIsDisplayed()
            onNodeWithTag(SwipeTestTags.TOP_CARD).assertIsDisplayed()
            // After the last card the viewer waits for their partner.
            onNodeWithTag(PsyncSessionTags.PASS_BUTTON).performClick()
            waitForIdle()
            onNodeWithTag(PsyncSessionTags.WAITING).assertExists()
        }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun endingASessionAsksOnceAndCanBeBackedOutOf() =
        runComposeUiTest {
            val repo = StaticPsyncRepository(current = session("active"), deck = listOf(card("a"), card("b")))
            setContent { PsyncSessionScreen(stateHolder = holder(repo), onBack = {}) }
            waitForIdle()

            onNodeWithTag(PsyncSessionTags.PAUSE_BUTTON).assertIsDisplayed()
            onNodeWithTag(PsyncSessionTags.END_SESSION_BUTTON).performClick()
            waitForIdle()
            onNodeWithTag(PsyncSessionTags.KEEP_GOING_BUTTON).performClick()
            waitForIdle()
            kotlin.test.assertEquals(0, repo.leaveCalls)

            onNodeWithTag(PsyncSessionTags.END_SESSION_BUTTON).performClick()
            waitForIdle()
            onNodeWithTag(PsyncSessionTags.CONFIRM_END_BUTTON).performClick()
            waitForIdle()
            kotlin.test.assertEquals(1, repo.leaveCalls)
        }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun afterTappingStartTheViewerSeesTheyAreWaiting() =
        runComposeUiTest {
            val ready =
                session("ready").copy(
                    participants =
                        listOf(
                            PsyncParticipantDto(VIEWER, "Viewer", "initiator", "accepted", true, started = true),
                            PsyncParticipantDto(PARTNER, "Pat", "partner", "accepted", true, started = false),
                        ),
                )
            setContent { PsyncSessionScreen(stateHolder = holder(StaticPsyncRepository(current = ready)), onBack = {}) }
            waitForIdle()

            onNodeWithText("Waiting for Pat").assertIsDisplayed()
            onNodeWithTag(PsyncSessionTags.WAITING).assertExists()
        }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun withoutALinkedPartnerTheScreenPointsToTheProfile() =
        runComposeUiTest {
            var openedHub = false
            val repo =
                object : PsyncRepository by StaticPsyncRepository(current = null) {
                    override suspend fun createSession(
                        deckSize: Int,
                        filters: SwipeDeckRequestDto,
                    ): AppResult<PsyncSessionDto> = AppResult.Failure(AppError("PSYNC_NO_LINKED_PARTNER", "Link a partner first."))
                }
            setContent { PsyncSessionScreen(stateHolder = holder(repo), onBack = {}, onOpenProfileHub = { openedHub = true }) }
            waitForIdle()

            onNodeWithTag(PsyncSessionTags.START_PSYNC_BUTTON).performClick()
            waitForIdle()
            onNodeWithText("Link your partner first").assertIsDisplayed()
            onNodeWithTag(PsyncSessionTags.LINK_PARTNER_BUTTON).performClick()
            kotlin.test.assertTrue(openedHub)
        }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun aTierLimitOffersThePlans() =
        runComposeUiTest {
            var openedStore = false
            val repo =
                object : PsyncRepository by StaticPsyncRepository(current = null) {
                    override suspend fun createSession(
                        deckSize: Int,
                        filters: SwipeDeckRequestDto,
                    ): AppResult<PsyncSessionDto> = AppResult.Failure(AppError("PSYNC_SESSION_LIMIT_REACHED", "You've used today's Psync sessions."))
                }
            setContent { PsyncSessionScreen(stateHolder = holder(repo), onBack = {}, onOpenStore = { openedStore = true }) }
            waitForIdle()

            onNodeWithTag(PsyncSessionTags.START_PSYNC_BUTTON).performClick()
            waitForIdle()
            onNodeWithTag(PsyncSessionTags.SEE_PLANS_BUTTON).performClick()
            kotlin.test.assertTrue(openedStore)
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
            // The viewer is in a live session that then finishes (their partner's last swipe).
            val repo = StaticPsyncRepository(current = session("active"), recap = recap)
            val stateHolder = holder(repo)
            kotlinx.coroutines.runBlocking { stateHolder.refresh() }
            repo.current = session("completed")
            setContent { PsyncSessionScreen(stateHolder = stateHolder, onBack = {}) }
            waitForIdle()

            onNodeWithTag(PsyncSessionTags.RECAP).assertIsDisplayed()
            // The together match opens that person's profile.
            onNodeWithTag(PsyncSessionTags.togetherMatchRow("t-a")).assertIsDisplayed()
            onNodeWithTag(PsyncSessionTags.START_ANOTHER_BUTTON).assertExists()
        }
}
