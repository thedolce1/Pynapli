package com.pynapli.app.psync

import com.pynapli.shared.core.result.AppError
import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.dto.CurrentPsyncSessionDto
import com.pynapli.shared.network.dto.PsyncActionResponseDto
import com.pynapli.shared.network.dto.PsyncDeckCardDto
import com.pynapli.shared.network.dto.PsyncDeckResponseDto
import com.pynapli.shared.network.dto.PsyncParticipantDto
import com.pynapli.shared.network.dto.PsyncRecapDto
import com.pynapli.shared.network.dto.PsyncSessionDto
import com.pynapli.shared.network.dto.SwipeCardDto
import com.pynapli.shared.network.dto.SwipeDeckRequestDto
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val VIEWER = "viewer-1"
private const val PARTNER = "partner-1"

private fun session(
    status: String,
    initiator: String = VIEWER,
    viewerApproved: Boolean = true,
    partnerApproved: Boolean = false,
    id: String = "session-1",
): PsyncSessionDto =
    PsyncSessionDto(
        id = id,
        partnerLinkId = "link-1",
        initiatorUserId = initiator,
        status = status,
        filters = SwipeDeckRequestDto(),
        deckSize = 25,
        createdAt = "2026-06-04T12:00:00Z",
        startedAt = null,
        expiresAt = "2026-06-05T12:00:00Z",
        participants =
            listOf(
                PsyncParticipantDto(VIEWER, "Viewer", "initiator", "accepted", viewerApproved),
                PsyncParticipantDto(PARTNER, "Pat", "partner", "pending", partnerApproved),
            ),
    )

private fun deckCard(token: String): PsyncDeckCardDto =
    PsyncDeckCardDto(
        card = SwipeCardDto(userId = "t-$token", displayName = "Target $token", rankToken = "r"),
        cardToken = token,
        viewerAction = null,
    )

private class FakePsyncRepository : PsyncRepository {
    var current: PsyncSessionDto? = null
    var deck: List<PsyncDeckCardDto> = emptyList()
    var recap: PsyncRecapDto? = null
    var actionResponse = PsyncActionResponseDto(recorded = true, actedCount = 1, deckSize = 25)
    var failNext: AppError? = null
    val calls = mutableListOf<String>()

    private fun <T> resultOf(value: T): AppResult<T> {
        val error = failNext
        return if (error != null) {
            failNext = null
            AppResult.Failure(error)
        } else {
            AppResult.Success(value)
        }
    }

    // What a by-id read returns once the session is no longer the "current" one.
    var ended: PsyncSessionDto? = null

    override suspend fun loadSession(sessionId: String): AppResult<PsyncSessionDto> {
        calls += "loadSession"
        return resultOf(ended ?: session("cancelled"))
    }

    override suspend fun loadCurrentSession(): AppResult<CurrentPsyncSessionDto> {
        calls += "loadCurrentSession"
        return resultOf(CurrentPsyncSessionDto(session = current))
    }

    override suspend fun createSession(deckSize: Int): AppResult<PsyncSessionDto> {
        calls += "createSession"
        return resultOf(current ?: session("invited"))
    }

    override suspend fun accept(sessionId: String) = recordSession("accept")

    override suspend fun deny(sessionId: String) = recordSession("deny")

    override suspend fun leave(sessionId: String) = recordSession("leave")

    override suspend fun start(sessionId: String) = recordSession("start")

    override suspend fun pause(sessionId: String) = recordSession("pause")

    override suspend fun resume(sessionId: String) = recordSession("resume")

    private fun recordSession(name: String): AppResult<PsyncSessionDto> {
        calls += name
        return resultOf(current ?: session("ready"))
    }

    override suspend fun loadDeck(sessionId: String): AppResult<PsyncDeckResponseDto> {
        calls += "loadDeck"
        return resultOf(PsyncDeckResponseDto(sessionId = sessionId, deckSize = 25, cards = deck))
    }

    override suspend fun submitAction(
        sessionId: String,
        cardToken: String,
        action: String,
    ): AppResult<PsyncActionResponseDto> {
        calls += "submitAction:$cardToken:$action"
        return resultOf(actionResponse)
    }

    override suspend fun complete(sessionId: String): AppResult<PsyncRecapDto> {
        calls += "complete"
        return resultOf(recap ?: error("no recap"))
    }

    override suspend fun loadRecap(sessionId: String): AppResult<PsyncRecapDto> {
        calls += "loadRecap"
        return resultOf(recap ?: error("no recap"))
    }

    override suspend fun nudge(sessionId: String): AppResult<Unit> {
        calls += "nudge"
        return resultOf(Unit)
    }
}

class PsyncStateHolderTest {
    private fun holder(repo: FakePsyncRepository) = PsyncStateHolder(repo, VIEWER)

    @Test
    fun refreshMapsCurrentSession() =
        runTest {
            val repo = FakePsyncRepository().apply { current = session("invited", partnerApproved = false) }
            val holder = holder(repo)

            holder.refresh()

            val state = holder.state.value
            assertEquals(PsyncPhase.INVITED, state.phase)
            assertTrue(state.session!!.isInitiator)
            assertEquals("Pat", state.session!!.partnerDisplayName)
            assertNull(state.errorMessage)
        }

    @Test
    fun refreshLoadsDeckWhenSessionActive() =
        runTest {
            val repo =
                FakePsyncRepository().apply {
                    current = session("active")
                    deck = listOf(deckCard("a"), deckCard("b"))
                }
            val holder = holder(repo)

            holder.refresh()

            assertEquals(2, holder.state.value.deck.size)
            assertTrue(repo.calls.contains("loadDeck"))
        }

    @Test
    fun awaitingViewerApprovalWhenInviteeHasNotApproved() =
        runTest {
            val repo =
                FakePsyncRepository().apply {
                    current = session("invited", initiator = PARTNER, viewerApproved = false, partnerApproved = true)
                }
            val holder = holder(repo)

            holder.refresh()

            val session = holder.state.value.session!!
            assertTrue(!session.isInitiator)
            assertTrue(session.awaitingViewerApproval)
        }

    @Test
    fun submitActionReflectsViewerChoiceAndLoadsRecapOnCompletion() =
        runTest {
            val repo =
                FakePsyncRepository().apply {
                    current = session("active")
                    deck = listOf(deckCard("a"))
                    actionResponse = PsyncActionResponseDto(recorded = true, actedCount = 1, deckSize = 1, sessionCompleted = true)
                    recap =
                        PsyncRecapDto(
                            sessionId = "session-1",
                            status = "completed",
                            profilesReviewed = 1,
                            bothLikedCount = 1,
                            togetherMatchCount = 1,
                            soloMatchCount = 0,
                            filtersSummary = "25km",
                            togetherMatches = emptyList(),
                        )
                }
            val holder = holder(repo)
            holder.refresh()

            holder.submitAction("a", "like")

            val state = holder.state.value
            assertEquals("like", state.deck.single().viewerAction)
            assertEquals(1, state.recap!!.togetherMatchCount)
            assertTrue(repo.calls.contains("loadRecap"))
            assertEquals(PsyncPhase.COMPLETED, state.phase)

            // The server no longer reports a finished session as current. A refresh (e.g. the
            // session_completed stream delta) must keep the recap on screen.
            repo.current = null
            holder.refresh()
            assertEquals(PsyncPhase.COMPLETED, holder.state.value.phase)
            assertEquals(
                1,
                holder.state.value.recap!!
                    .togetherMatchCount,
            )

            // Dismissing the recap frees the screen for a new session.
            holder.dismissFinished()
            assertEquals(PsyncPhase.IDLE, holder.state.value.phase)
        }

    @Test
    fun refreshFollowsASessionThatEndedElsewhereToItsRecap() =
        runTest {
            val repo =
                FakePsyncRepository().apply {
                    current = session("active")
                    deck = listOf(deckCard("a"))
                    recap =
                        PsyncRecapDto(
                            sessionId = "session-1",
                            status = "completed",
                            profilesReviewed = 1,
                            bothLikedCount = 0,
                            togetherMatchCount = 0,
                            soloMatchCount = 0,
                            filtersSummary = "25km",
                            togetherMatches = emptyList(),
                        )
                }
            val holder = holder(repo)
            holder.refresh()
            assertEquals(PsyncPhase.ACTIVE, holder.state.value.phase)

            // The partner left (or finished): the session is resolved server-side.
            repo.current = null
            repo.ended = session("completed")
            holder.refresh()

            assertEquals(PsyncPhase.COMPLETED, holder.state.value.phase)
            assertTrue(repo.calls.contains("loadSession"))
            assertEquals(
                1,
                holder.state.value.recap!!
                    .profilesReviewed,
            )
        }

    @Test
    fun aRefusedSwipePutsTheCardBack() =
        runTest {
            val repo =
                FakePsyncRepository().apply {
                    current = session("active")
                    deck = listOf(deckCard("a"))
                }
            val holder = holder(repo)
            holder.refresh()

            repo.failNext = AppError("PINEAPPLE_LIKE_INVENTORY_REQUIRED", "You do not have a Pineapple Like available.")
            holder.submitAction("a", "pineapple_like")

            assertEquals(
                null,
                holder.state.value.deck
                    .single()
                    .viewerAction,
            )
            assertEquals(1, holder.state.value.remainingCards.size)
            assertEquals("You do not have a Pineapple Like available.", holder.state.value.errorMessage)
        }

    @Test
    fun failureSurfacesErrorMessage() =
        runTest {
            val repo = FakePsyncRepository().apply { failNext = AppError("PSYNC_NO_LINKED_PARTNER", "Need a partner.") }
            val holder = holder(repo)

            holder.createSession()

            assertEquals("Need a partner.", holder.state.value.errorMessage)
            assertNull(holder.state.value.session)
        }
}
