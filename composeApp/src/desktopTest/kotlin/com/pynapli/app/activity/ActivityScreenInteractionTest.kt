package com.pynapli.app.activity

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.pynapli.app.design.PynapliTheme
import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.dto.ActivityEventDto
import com.pynapli.shared.network.dto.DismissActivityEventResponseDto
import com.pynapli.shared.network.dto.MarkActivitySeenResponseDto
import com.pynapli.shared.network.dto.RespondAlbumAccessResponseDto
import kotlin.test.Test
import kotlin.test.assertEquals

class ActivityScreenInteractionTest {

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun activityTabsLoadMoreAndDismissUseServerBackedState() = runComposeUiTest {
        val repository = StaticActivityScreenRepository(
            responses = mutableMapOf(
                ActivityRequest(ActivityTabUi.All, null) to ActivityFeedUi(
                    items = listOf(activityEvent("view-1", "profile_view", "Maya"))
                ),
                ActivityRequest(ActivityTabUi.Flirts, null) to ActivityFeedUi(
                    items = listOf(activityEvent("flirt-1", "flirt", "Alex")),
                    nextCursor = "flirts-page-2"
                ),
                ActivityRequest(ActivityTabUi.Flirts, "flirts-page-2") to ActivityFeedUi(
                    items = listOf(activityEvent("flirt-2", "flirt", "Sam"))
                )
            )
        )
        val stateHolder = ActivityStateHolder(repository)

        setContent {
            PynapliTheme(themeMode = "dark") {
                Box(modifier = Modifier.size(width = 390.dp, height = 844.dp)) {
                    ActivityScreen(stateHolder = stateHolder)
                }
            }
        }

        waitUntil(timeoutMillis = 5_000) { repository.loadCalls.contains(ActivityRequest(ActivityTabUi.All, null)) }
        onNodeWithText("Maya").assertExists()

        onNodeWithText("Flirts").performClick()
        waitUntil(timeoutMillis = 5_000) { repository.loadCalls.contains(ActivityRequest(ActivityTabUi.Flirts, null)) }
        onNodeWithText("Alex").assertExists()
        onNodeWithText("Maya").assertDoesNotExist()

        onNodeWithText("Load more").performClick()
        waitUntil(timeoutMillis = 5_000) {
            repository.loadCalls.contains(ActivityRequest(ActivityTabUi.Flirts, "flirts-page-2")) &&
                stateHolder.state.value.items.any { it.eventId == "flirt-2" }
        }
        onNodeWithText("Sam").assertExists()

        onAllNodesWithContentDescription("Dismiss activity")[0].performClick()
        waitUntil(timeoutMillis = 5_000) { repository.dismissCalls == listOf("flirt-1") }
        waitUntil(timeoutMillis = 5_000) { stateHolder.state.value.items.none { it.eventId == "flirt-1" } }
        onNodeWithText("Alex").assertDoesNotExist()

        assertEquals(
            listOf(
                ActivityRequest(ActivityTabUi.All, null),
                ActivityRequest(ActivityTabUi.Flirts, null),
                ActivityRequest(ActivityTabUi.Flirts, "flirts-page-2")
            ),
            repository.loadCalls
        )
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun activityRowCtasOpenExpectedActions() = runComposeUiTest {
        val repository = StaticActivityScreenRepository(
            responses = mutableMapOf(
                ActivityRequest(ActivityTabUi.All, null) to ActivityFeedUi(
                    items = listOf(
                        activityEvent(
                            eventId = "match-1",
                            type = "match",
                            displayName = "Taylor",
                            matchId = "match-id-1"
                        ),
                        activityEvent(
                            eventId = "album-1",
                            type = "album_request",
                            displayName = "Sam",
                            albumRequestStatus = "pending"
                        ),
                        activityEvent(
                            eventId = "invite-1",
                            type = "partner_chat_invite",
                            displayName = "Noa",
                            conversationId = "conversation-1"
                        )
                    )
                )
            )
        )
        val stateHolder = ActivityStateHolder(repository)
        val openedMatches = mutableListOf<ActivityEventDto>()
        val openedInvites = mutableListOf<String>()

        setContent {
            PynapliTheme(themeMode = "dark") {
                Box(modifier = Modifier.size(width = 390.dp, height = 844.dp)) {
                    ActivityScreen(
                        stateHolder = stateHolder,
                        onOpenMatch = { openedMatches += it },
                        onOpenPartnerChatInvite = { openedInvites += it }
                    )
                }
            }
        }

        waitUntil(timeoutMillis = 5_000) { repository.loadCalls.contains(ActivityRequest(ActivityTabUi.All, null)) }
        onNodeWithText("Message").performClick()
        onNodeWithText("Open invite").performClick()
        onNodeWithText("Review").performClick()
        onNodeWithText("Grant").performClick()

        waitUntil(timeoutMillis = 5_000) { repository.respondCalls == listOf("album-1" to true) }
        assertEquals(listOf("match-1"), openedMatches.map { it.eventId })
        assertEquals(listOf("conversation-1"), openedInvites)
    }
}

private class StaticActivityScreenRepository(
    private val responses: Map<ActivityRequest, ActivityFeedUi>
) : ActivityFeedRepository {
    val loadCalls = mutableListOf<ActivityRequest>()
    val dismissCalls = mutableListOf<String>()
    val respondCalls = mutableListOf<Pair<String, Boolean>>()

    override suspend fun loadActivity(
        tab: ActivityTabUi,
        cursor: String?,
        limit: Int
    ): AppResult<ActivityFeedUi> {
        val request = ActivityRequest(tab, cursor)
        loadCalls += request
        return AppResult.Success(responses[request] ?: ActivityFeedUi(items = emptyList()))
    }

    override suspend fun loadSummary(): AppResult<ActivitySummaryUi> =
        AppResult.Success(ActivitySummaryUi.Empty)

    override suspend fun markActivitySeen(eventIds: List<String>): AppResult<MarkActivitySeenResponseDto> =
        AppResult.Success(MarkActivitySeenResponseDto(markedCount = eventIds.distinct().size))

    override suspend fun dismissActivityEvent(eventId: String): AppResult<DismissActivityEventResponseDto> {
        dismissCalls += eventId
        return AppResult.Success(DismissActivityEventResponseDto(eventId = eventId, dismissed = true))
    }

    override suspend fun respondToAlbumRequest(
        eventId: String,
        approve: Boolean
    ): AppResult<RespondAlbumAccessResponseDto> {
        respondCalls += eventId to approve
        return AppResult.Success(
            RespondAlbumAccessResponseDto(
                eventId = eventId,
                status = if (approve) "approved" else "denied"
            )
        )
    }
}

private data class ActivityRequest(
    val tab: ActivityTabUi,
    val cursor: String?
)

private fun activityEvent(
    eventId: String,
    type: String,
    displayName: String,
    albumRequestStatus: String? = null,
    matchId: String? = null,
    conversationId: String? = null
): ActivityEventDto =
    ActivityEventDto(
        eventId = eventId,
        type = type,
        actorUserId = "$eventId-actor",
        actorDisplayName = displayName,
        occurredAt = "2026-05-19T17:30:00Z",
        albumRequestStatus = albumRequestStatus,
        matchId = matchId,
        conversationId = conversationId
    )
