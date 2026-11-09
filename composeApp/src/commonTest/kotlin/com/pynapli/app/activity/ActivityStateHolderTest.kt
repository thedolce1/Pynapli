package com.pynapli.app.activity

import com.pynapli.shared.core.result.AppError
import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.dto.ActivityEventDto
import com.pynapli.shared.network.dto.DismissActivityEventResponseDto
import com.pynapli.shared.network.dto.MarkActivitySeenResponseDto
import com.pynapli.shared.network.dto.RespondAlbumAccessResponseDto
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class ActivityStateHolderTest {
    @Test
    fun silentRefreshKeepsExistingFeedWhenRefreshFails() =
        runTest {
            val event = activityEvent(eventId = "event-1")
            val repository =
                FakeActivityFeedRepository(
                    result = AppResult.Success(ActivityFeedUi(items = listOf(event))),
                )
            val stateHolder = ActivityStateHolder(repository)

            stateHolder.load()
            repository.result = AppResult.Failure(AppError(code = "network_error", message = "Offline"))
            stateHolder.refreshSilently()

            val state = stateHolder.state.value
            assertEquals(listOf(event), state.items)
            assertFalse(state.isLoading)
            assertNull(state.errorMessage)
        }

    @Test
    fun loadStoresEntitlementMetadata() =
        runTest {
            val event = activityEvent(eventId = "event-1")
            val repository =
                FakeActivityFeedRepository(
                    result =
                        AppResult.Success(
                            ActivityFeedUi(
                                items = listOf(event),
                                nextCursor = "cursor-2",
                                adSupported = true,
                                entitlementTier = "free",
                                revealLimit = 1,
                                gatedCount = 4,
                            ),
                        ),
                )
            val stateHolder = ActivityStateHolder(repository)

            stateHolder.load()

            val state = stateHolder.state.value
            assertEquals(listOf(event), state.items)
            assertEquals("cursor-2", state.nextCursor)
            assertEquals(true, state.adSupported)
            assertEquals("free", state.entitlementTier)
            assertEquals(1, state.revealLimit)
            assertEquals(4, state.gatedCount)
        }

    @Test
    fun loadStoresServerBackedActivitySummary() =
        runTest {
            val repository =
                FakeActivityFeedRepository(
                    result = AppResult.Success(ActivityFeedUi(items = emptyList())),
                    summaryResult =
                        AppResult.Success(
                            ActivitySummaryUi(
                                badgeCount = 5,
                                profileViewCount = 12,
                                flirtCount = 1,
                                likeCount = 2,
                                matchCount = 1,
                                albumRequestCount = 1,
                                partnerInviteCount = 0,
                                latestBadgeOccurredAt = "2026-05-19T12:30:00Z",
                            ),
                        ),
                )
            val stateHolder = ActivityStateHolder(repository)

            stateHolder.load()

            val state = stateHolder.state.value
            assertEquals(1, repository.summaryCalls)
            assertEquals(5, state.activityBadgeCount)
            assertEquals(12, state.summary.profileViewCount)
            assertEquals("2026-05-19T12:30:00Z", state.summary.latestBadgeOccurredAt)
        }

    @Test
    fun visibleActivityLoadMarksLoadedEventsSeenBeforeRefreshingSummary() =
        runTest {
            val first = activityEvent(eventId = "event-1", type = "flirt")
            val second = activityEvent(eventId = "event-2", type = "match")
            val repository =
                FakeActivityFeedRepository(
                    result = AppResult.Success(ActivityFeedUi(items = listOf(first, second))),
                    summaryResult =
                        AppResult.Success(
                            ActivitySummaryUi(
                                badgeCount = 0,
                                profileViewCount = 0,
                                flirtCount = 0,
                                likeCount = 0,
                                matchCount = 0,
                                albumRequestCount = 0,
                                partnerInviteCount = 0,
                                latestBadgeOccurredAt = null,
                            ),
                        ),
                )
            val stateHolder = ActivityStateHolder(repository)

            stateHolder.load()

            assertEquals(listOf(listOf("event-1", "event-2")), repository.markSeenCalls)
            assertEquals(1, repository.summaryCalls)
            assertEquals(0, stateHolder.state.value.activityBadgeCount)
        }

    @Test
    fun silentFeedRefreshDoesNotMarkEventsSeen() =
        runTest {
            val first = activityEvent(eventId = "event-1", type = "flirt")
            val repository =
                FakeActivityFeedRepository(
                    result = AppResult.Success(ActivityFeedUi(items = listOf(first))),
                )
            val stateHolder = ActivityStateHolder(repository)

            stateHolder.refreshSilently()

            assertEquals(emptyList(), repository.markSeenCalls)
            assertEquals(0, repository.summaryCalls)
            assertEquals(listOf(first), stateHolder.state.value.items)
        }

    @Test
    fun silentSummaryRefreshKeepsExistingBadgeWhenSummaryFails() =
        runTest {
            val repository =
                FakeActivityFeedRepository(
                    summaryResult =
                        AppResult.Success(
                            ActivitySummaryUi(
                                badgeCount = 3,
                                profileViewCount = 4,
                                flirtCount = 1,
                                likeCount = 1,
                                matchCount = 1,
                                albumRequestCount = 0,
                                partnerInviteCount = 0,
                                latestBadgeOccurredAt = null,
                            ),
                        ),
                )
            val stateHolder = ActivityStateHolder(repository)

            stateHolder.refreshSummarySilently()
            repository.summaryResult = AppResult.Failure(AppError(code = "network_error", message = "Offline"))
            stateHolder.refreshSummarySilently()

            assertEquals(3, stateHolder.state.value.activityBadgeCount)
            assertNull(stateHolder.state.value.errorMessage)
        }

    @Test
    fun resetClearsServerBackedSummaryAndFeed() =
        runTest {
            val event = activityEvent(eventId = "event-1")
            val repository =
                FakeActivityFeedRepository(
                    result = AppResult.Success(ActivityFeedUi(items = listOf(event))),
                    summaryResult =
                        AppResult.Success(
                            ActivitySummaryUi(
                                badgeCount = 4,
                                profileViewCount = 1,
                                flirtCount = 1,
                                likeCount = 1,
                                matchCount = 1,
                                albumRequestCount = 1,
                                partnerInviteCount = 0,
                                latestBadgeOccurredAt = null,
                            ),
                        ),
                )
            val stateHolder = ActivityStateHolder(repository)

            stateHolder.load()
            stateHolder.reset()

            assertEquals(emptyList(), stateHolder.state.value.items)
            assertEquals(0, stateHolder.state.value.activityBadgeCount)
        }

    @Test
    fun selectingTabQueriesBackendTabAndReplacesFeed() =
        runTest {
            val viewEvent = activityEvent(eventId = "view-1", type = "profile_view")
            val repository =
                FakeActivityFeedRepository(
                    result = AppResult.Success(ActivityFeedUi(items = listOf(activityEvent(eventId = "event-1")))),
                )
            val stateHolder = ActivityStateHolder(repository)

            stateHolder.load()
            repository.result = AppResult.Success(ActivityFeedUi(items = listOf(viewEvent)))
            stateHolder.selectTab(ActivityTabUi.Views)

            assertEquals(listOf(ActivityTabUi.Flirts, ActivityTabUi.Views), repository.loadCalls.map { it.tab })
            assertEquals(null, repository.loadCalls.last().cursor)
            assertEquals(ActivityTabUi.Views, stateHolder.state.value.selectedTab)
            assertEquals(listOf(viewEvent), stateHolder.state.value.items)
        }

    @Test
    fun loadMoreUsesCursorAndAppendsUniqueItems() =
        runTest {
            val first = activityEvent(eventId = "event-1")
            val second = activityEvent(eventId = "event-2")
            val repository =
                FakeActivityFeedRepository(
                    results =
                        mutableListOf(
                            AppResult.Success(ActivityFeedUi(items = listOf(first), nextCursor = "cursor-2")),
                            AppResult.Success(ActivityFeedUi(items = listOf(first, second), nextCursor = null)),
                        ),
                )
            val stateHolder = ActivityStateHolder(repository)

            stateHolder.load()
            stateHolder.loadMore()

            assertEquals(listOf(null, "cursor-2"), repository.loadCalls.map { it.cursor })
            assertEquals(listOf(listOf("event-1"), listOf("event-2")), repository.markSeenCalls)
            assertEquals(listOf(first, second), stateHolder.state.value.items)
            assertEquals(null, stateHolder.state.value.nextCursor)
            assertFalse(stateHolder.state.value.isLoadingMore)
        }

    @Test
    fun dismissActivityEventRemovesItemFromFeed() =
        runTest {
            val kept = activityEvent(eventId = "event-1")
            val dismissed = activityEvent(eventId = "event-2")
            val repository =
                FakeActivityFeedRepository(
                    result = AppResult.Success(ActivityFeedUi(items = listOf(kept, dismissed))),
                )
            val stateHolder = ActivityStateHolder(repository)

            stateHolder.load()
            stateHolder.dismissActivityEvent("event-2")

            assertEquals(listOf("event-2"), repository.dismissCalls)
            assertEquals(listOf(kept), stateHolder.state.value.items)
            assertEquals("Activity dismissed", stateHolder.state.value.actionFeedback)
            assertNull(stateHolder.state.value.pendingDismissEventId)
        }
}

private class FakeActivityFeedRepository(
    var result: AppResult<ActivityFeedUi> = AppResult.Success(ActivityFeedUi(items = emptyList())),
    var summaryResult: AppResult<ActivitySummaryUi> = AppResult.Success(ActivitySummaryUi.Empty),
    var markSeenResult: AppResult<MarkActivitySeenResponseDto> =
        AppResult.Success(
            MarkActivitySeenResponseDto(markedCount = 0),
        ),
    private val results: MutableList<AppResult<ActivityFeedUi>> = mutableListOf(),
) : ActivityFeedRepository {
    val loadCalls = mutableListOf<ActivityLoadCall>()
    val dismissCalls = mutableListOf<String>()
    val markSeenCalls = mutableListOf<List<String>>()
    var summaryCalls = 0

    override suspend fun loadActivity(
        tab: ActivityTabUi,
        cursor: String?,
        limit: Int,
    ): AppResult<ActivityFeedUi> {
        loadCalls += ActivityLoadCall(tab = tab, cursor = cursor, limit = limit)
        return if (results.isNotEmpty()) {
            results.removeAt(0)
        } else {
            result
        }
    }

    override suspend fun loadSummary(): AppResult<ActivitySummaryUi> {
        summaryCalls += 1
        return summaryResult
    }

    override suspend fun markActivitySeen(eventIds: List<String>): AppResult<MarkActivitySeenResponseDto> {
        markSeenCalls += eventIds
        return markSeenResult
    }

    override suspend fun dismissActivityEvent(eventId: String): AppResult<DismissActivityEventResponseDto> {
        dismissCalls += eventId
        return AppResult.Success(
            DismissActivityEventResponseDto(
                eventId = eventId,
                dismissed = true,
            ),
        )
    }

    override suspend fun respondToAlbumRequest(
        eventId: String,
        approve: Boolean,
    ): AppResult<RespondAlbumAccessResponseDto> =
        AppResult.Success(
            RespondAlbumAccessResponseDto(
                eventId = eventId,
                status = if (approve) "approved" else "denied",
            ),
        )
}

private data class ActivityLoadCall(
    val tab: ActivityTabUi,
    val cursor: String?,
    val limit: Int,
)

private fun activityEvent(
    eventId: String,
    type: String = "profile_view",
): ActivityEventDto =
    ActivityEventDto(
        eventId = eventId,
        type = type,
        actorUserId = "actor-1",
        actorDisplayName = "Maya",
        occurredAt = "2026-05-01T17:30:00Z",
    )
