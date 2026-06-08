package com.pynapli.app.activity

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
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
    fun flirtsGridLoadMoreDismissAndTabSwitch() =
        runComposeUiTest {
            val repository =
                StaticActivityScreenRepository(
                    responses =
                        mutableMapOf(
                            ActivityRequest(ActivityTabUi.Flirts, null) to
                                ActivityFeedUi(
                                    items = listOf(activityEvent("flirt-1", "flirt", "Alex")),
                                    nextCursor = "flirts-page-2",
                                ),
                            ActivityRequest(ActivityTabUi.Flirts, "flirts-page-2") to
                                ActivityFeedUi(items = listOf(activityEvent("flirt-2", "flirt", "Sam"))),
                            ActivityRequest(ActivityTabUi.Views, null) to
                                ActivityFeedUi(items = listOf(activityEvent("view-1", "profile_view", "Maya"))),
                        ),
                )
            val stateHolder = ActivityStateHolder(repository)

            setContent {
                PynapliTheme(themeMode = "dark") {
                    Box(modifier = Modifier.size(width = 390.dp, height = 844.dp)) {
                        ActivityScreen(stateHolder = stateHolder)
                    }
                }
            }

            // Flirts is the default tab (and a FOMO grid).
            waitUntil(timeoutMillis = 5_000) { repository.loadCalls.contains(ActivityRequest(ActivityTabUi.Flirts, null)) }
            onNodeWithText("Alex").assertExists()

            onNodeWithText("Load more").performClick()
            waitUntil(timeoutMillis = 5_000) {
                repository.loadCalls.contains(ActivityRequest(ActivityTabUi.Flirts, "flirts-page-2")) &&
                    stateHolder.state.value.items
                        .any { it.eventId == "flirt-2" }
            }
            onNodeWithText("Sam").assertExists()

            onAllNodesWithContentDescription("Dismiss activity")[0].performClick()
            waitUntil(timeoutMillis = 5_000) { repository.dismissCalls == listOf("flirt-1") }
            waitUntil(timeoutMillis = 5_000) {
                stateHolder.state.value.items
                    .none { it.eventId == "flirt-1" }
            }
            onNodeWithText("Alex").assertDoesNotExist()

            onNodeWithText("Views").performClick()
            waitUntil(timeoutMillis = 5_000) { repository.loadCalls.contains(ActivityRequest(ActivityTabUi.Views, null)) }
            onNodeWithText("Maya").assertExists()

            assertEquals(
                listOf(
                    ActivityRequest(ActivityTabUi.Flirts, null),
                    ActivityRequest(ActivityTabUi.Flirts, "flirts-page-2"),
                    ActivityRequest(ActivityTabUi.Views, null),
                ),
                repository.loadCalls,
            )
        }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun requestsTabCtasOpenInviteAndAlbumActions() =
        runComposeUiTest {
            val repository =
                StaticActivityScreenRepository(
                    responses =
                        mutableMapOf(
                            ActivityRequest(ActivityTabUi.Flirts, null) to ActivityFeedUi(items = emptyList()),
                            ActivityRequest(ActivityTabUi.Requests, null) to
                                ActivityFeedUi(
                                    items =
                                        listOf(
                                            activityEvent(
                                                eventId = "invite-1",
                                                type = "partner_chat_invite",
                                                displayName = "Noa",
                                                conversationId = "conversation-1",
                                            ),
                                            activityEvent(
                                                eventId = "album-1",
                                                type = "album_request",
                                                displayName = "Sam",
                                                albumRequestStatus = "pending",
                                            ),
                                        ),
                                ),
                        ),
                )
            val stateHolder = ActivityStateHolder(repository)
            val openedInvites = mutableListOf<String>()

            setContent {
                PynapliTheme(themeMode = "dark") {
                    Box(modifier = Modifier.size(width = 390.dp, height = 844.dp)) {
                        ActivityScreen(
                            stateHolder = stateHolder,
                            onOpenPartnerChatInvite = { openedInvites += it },
                        )
                    }
                }
            }

            waitUntil(timeoutMillis = 5_000) { repository.loadCalls.contains(ActivityRequest(ActivityTabUi.Flirts, null)) }
            onNodeWithText("Requests").performClick()
            waitUntil(timeoutMillis = 5_000) { repository.loadCalls.contains(ActivityRequest(ActivityTabUi.Requests, null)) }

            onNodeWithText("Open invite").performClick()
            onNodeWithText("Review").performClick()
            onNodeWithText("Grant").performClick()

            waitUntil(timeoutMillis = 5_000) { repository.respondCalls == listOf("album-1" to true) }
            assertEquals(listOf("conversation-1"), openedInvites)
        }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun flirtsGridShowsLockedTilesAndUnlockBanner() =
        runComposeUiTest {
            var openedStore = false
            val repository =
                StaticActivityScreenRepository(
                    responses =
                        mutableMapOf(
                            ActivityRequest(ActivityTabUi.Flirts, null) to
                                ActivityFeedUi(
                                    items = listOf(activityEvent("flirt-new", "flirt", "Alex", seen = false)),
                                    entitlementTier = "free",
                                    revealLimit = 1,
                                    gatedCount = 4,
                                ),
                        ),
                )
            val stateHolder = ActivityStateHolder(repository)

            setContent {
                PynapliTheme(themeMode = "dark") {
                    Box(modifier = Modifier.size(width = 390.dp, height = 844.dp)) {
                        ActivityScreen(
                            stateHolder = stateHolder,
                            onOpenStore = { openedStore = true },
                        )
                    }
                }
            }

            waitUntil(timeoutMillis = 5_000) { repository.loadCalls.contains(ActivityRequest(ActivityTabUi.Flirts, null)) }
            // Unseen real tile shows a New pill; gatedCount = 4 frosted locked tiles drive FOMO.
            onNodeWithText("New").assertExists()
            onAllNodesWithTag(ActivityFomoTags.LOCKED_TILE).assertCountEquals(4)

            // Tapping a locked teaser tile routes to the Store (the upgrade path).
            onAllNodesWithTag(ActivityFomoTags.LOCKED_TILE)[0].performClick()
            assertEquals(true, openedStore)
        }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun viaPlayingTonightChipOnlyForTaggedFlirts() =
        runComposeUiTest {
            val repository =
                StaticActivityScreenRepository(
                    responses =
                        mutableMapOf(
                            ActivityRequest(ActivityTabUi.Flirts, null) to
                                ActivityFeedUi(
                                    items =
                                        listOf(
                                            activityEvent("flirt-tonight", "flirt", "Alex", source = "playing_tonight"),
                                            activityEvent("flirt-plain", "flirt", "Sam"),
                                        ),
                                ),
                        ),
                )
            val stateHolder = ActivityStateHolder(repository)

            setContent {
                PynapliTheme(themeMode = "dark") {
                    Box(modifier = Modifier.size(width = 390.dp, height = 844.dp)) {
                        ActivityScreen(stateHolder = stateHolder)
                    }
                }
            }

            waitUntil(timeoutMillis = 5_000) { repository.loadCalls.contains(ActivityRequest(ActivityTabUi.Flirts, null)) }
            onNodeWithText("Alex").assertExists()
            onNodeWithText("Sam").assertExists()
            // The caption tag lives on a child of the clickable tile, so look in the unmerged tree.
            onAllNodesWithTag(VIA_PLAYING_TONIGHT_TEST_TAG, useUnmergedTree = true).assertCountEquals(1)
            onNodeWithText("via Playing Tonight").assertExists()
        }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun viewsTabRendersFomoGridWithLockedTilesAndTapOpensProfile() =
        runComposeUiTest {
            val repository =
                StaticActivityScreenRepository(
                    responses =
                        mutableMapOf(
                            ActivityRequest(ActivityTabUi.Flirts, null) to ActivityFeedUi(items = emptyList()),
                            ActivityRequest(ActivityTabUi.Views, null) to
                                ActivityFeedUi(
                                    items = listOf(activityEvent("view-1", "profile_view", "Maya")),
                                    gatedCount = 4,
                                ),
                        ),
                )
            val stateHolder = ActivityStateHolder(repository)
            val openedProfiles = mutableListOf<String>()

            setContent {
                PynapliTheme(themeMode = "dark") {
                    Box(modifier = Modifier.size(width = 390.dp, height = 844.dp)) {
                        ActivityScreen(
                            stateHolder = stateHolder,
                            onOpenProfile = { event -> openedProfiles += event.actorUserId },
                        )
                    }
                }
            }

            waitUntil(timeoutMillis = 5_000) { repository.loadCalls.contains(ActivityRequest(ActivityTabUi.Flirts, null)) }
            onNodeWithText("Views").performClick()
            waitUntil(timeoutMillis = 5_000) { repository.loadCalls.contains(ActivityRequest(ActivityTabUi.Views, null)) }

            onNodeWithTag(ActivityFomoTags.GRID).assertExists()
            onNodeWithText("Maya").assertExists()
            // gatedCount = 4 frosted teaser tiles
            onAllNodesWithTag(ActivityFomoTags.LOCKED_TILE).assertCountEquals(4)

            onNodeWithTag(ActivityFomoTags.tile("view-1")).performClick()
            waitUntil(timeoutMillis = 5_000) { openedProfiles == listOf("view-1-actor") }
        }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun matchesTabTileOpensConversation() =
        runComposeUiTest {
            val repository =
                StaticActivityScreenRepository(
                    responses =
                        mutableMapOf(
                            ActivityRequest(ActivityTabUi.Flirts, null) to ActivityFeedUi(items = emptyList()),
                            ActivityRequest(ActivityTabUi.Matches, null) to
                                ActivityFeedUi(
                                    items = listOf(activityEvent("match-1", "match", "Taylor", matchId = "match-id-1")),
                                ),
                        ),
                )
            val stateHolder = ActivityStateHolder(repository)
            val openedMatches = mutableListOf<String>()

            setContent {
                PynapliTheme(themeMode = "dark") {
                    Box(modifier = Modifier.size(width = 390.dp, height = 844.dp)) {
                        ActivityScreen(
                            stateHolder = stateHolder,
                            onOpenMatch = { openedMatches += it.eventId },
                        )
                    }
                }
            }

            waitUntil(timeoutMillis = 5_000) { repository.loadCalls.contains(ActivityRequest(ActivityTabUi.Flirts, null)) }
            onNodeWithText("Matches").performClick()
            waitUntil(timeoutMillis = 5_000) { repository.loadCalls.contains(ActivityRequest(ActivityTabUi.Matches, null)) }

            onNodeWithTag(ActivityFomoTags.GRID).assertExists()
            onNodeWithTag(ActivityFomoTags.tile("match-1")).performClick()
            waitUntil(timeoutMillis = 5_000) { openedMatches == listOf("match-1") }
        }
}

private class StaticActivityScreenRepository(
    private val responses: Map<ActivityRequest, ActivityFeedUi>,
) : ActivityFeedRepository {
    val loadCalls = mutableListOf<ActivityRequest>()
    val dismissCalls = mutableListOf<String>()
    val respondCalls = mutableListOf<Pair<String, Boolean>>()

    override suspend fun loadActivity(
        tab: ActivityTabUi,
        cursor: String?,
        limit: Int,
    ): AppResult<ActivityFeedUi> {
        val request = ActivityRequest(tab, cursor)
        loadCalls += request
        return AppResult.Success(responses[request] ?: ActivityFeedUi(items = emptyList()))
    }

    override suspend fun loadSummary(): AppResult<ActivitySummaryUi> = AppResult.Success(ActivitySummaryUi.Empty)

    override suspend fun markActivitySeen(eventIds: List<String>): AppResult<MarkActivitySeenResponseDto> = AppResult.Success(MarkActivitySeenResponseDto(markedCount = eventIds.distinct().size))

    override suspend fun dismissActivityEvent(eventId: String): AppResult<DismissActivityEventResponseDto> {
        dismissCalls += eventId
        return AppResult.Success(DismissActivityEventResponseDto(eventId = eventId, dismissed = true))
    }

    override suspend fun respondToAlbumRequest(
        eventId: String,
        approve: Boolean,
    ): AppResult<RespondAlbumAccessResponseDto> {
        respondCalls += eventId to approve
        return AppResult.Success(
            RespondAlbumAccessResponseDto(
                eventId = eventId,
                status = if (approve) "approved" else "denied",
            ),
        )
    }
}

private data class ActivityRequest(
    val tab: ActivityTabUi,
    val cursor: String?,
)

private fun activityEvent(
    eventId: String,
    type: String,
    displayName: String,
    albumRequestStatus: String? = null,
    matchId: String? = null,
    conversationId: String? = null,
    seen: Boolean = false,
    source: String? = null,
): ActivityEventDto =
    ActivityEventDto(
        eventId = eventId,
        type = type,
        actorUserId = "$eventId-actor",
        actorDisplayName = displayName,
        occurredAt = "2026-05-19T17:30:00Z",
        seen = seen,
        albumRequestStatus = albumRequestStatus,
        matchId = matchId,
        conversationId = conversationId,
        source = source,
    )
