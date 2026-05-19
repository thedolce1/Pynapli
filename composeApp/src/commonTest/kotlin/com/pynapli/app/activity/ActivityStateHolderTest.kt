package com.pynapli.app.activity

import com.pynapli.shared.core.result.AppError
import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.dto.ActivityEventDto
import com.pynapli.shared.network.dto.DismissActivityEventResponseDto
import com.pynapli.shared.network.dto.RespondAlbumAccessResponseDto
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class ActivityStateHolderTest {
    @Test
    fun silentRefreshKeepsExistingFeedWhenRefreshFails() = runBlocking {
        val event = activityEvent(eventId = "event-1")
        val repository = FakeActivityFeedRepository(
            result = AppResult.Success(ActivityFeedUi(items = listOf(event)))
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
    fun loadStoresEntitlementMetadata() = runBlocking {
        val event = activityEvent(eventId = "event-1")
        val repository = FakeActivityFeedRepository(
            result = AppResult.Success(
                ActivityFeedUi(
                    items = listOf(event),
                    nextCursor = "cursor-2",
                    adSupported = true,
                    entitlementTier = "free",
                    revealLimit = 1,
                    gatedCount = 4
                )
            )
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
    fun selectingTabQueriesBackendTabAndReplacesFeed() = runBlocking {
        val flirtEvent = activityEvent(eventId = "flirt-1", type = "flirt")
        val repository = FakeActivityFeedRepository(
            result = AppResult.Success(ActivityFeedUi(items = listOf(activityEvent(eventId = "event-1"))))
        )
        val stateHolder = ActivityStateHolder(repository)

        stateHolder.load()
        repository.result = AppResult.Success(ActivityFeedUi(items = listOf(flirtEvent)))
        stateHolder.selectTab(ActivityTabUi.Flirts)

        assertEquals(listOf(ActivityTabUi.All, ActivityTabUi.Flirts), repository.loadCalls.map { it.tab })
        assertEquals(null, repository.loadCalls.last().cursor)
        assertEquals(ActivityTabUi.Flirts, stateHolder.state.value.selectedTab)
        assertEquals(listOf(flirtEvent), stateHolder.state.value.items)
    }

    @Test
    fun loadMoreUsesCursorAndAppendsUniqueItems() = runBlocking {
        val first = activityEvent(eventId = "event-1")
        val second = activityEvent(eventId = "event-2")
        val repository = FakeActivityFeedRepository(
            results = mutableListOf(
                AppResult.Success(ActivityFeedUi(items = listOf(first), nextCursor = "cursor-2")),
                AppResult.Success(ActivityFeedUi(items = listOf(first, second), nextCursor = null))
            )
        )
        val stateHolder = ActivityStateHolder(repository)

        stateHolder.load()
        stateHolder.loadMore()

        assertEquals(listOf(null, "cursor-2"), repository.loadCalls.map { it.cursor })
        assertEquals(listOf(first, second), stateHolder.state.value.items)
        assertEquals(null, stateHolder.state.value.nextCursor)
        assertFalse(stateHolder.state.value.isLoadingMore)
    }

    @Test
    fun dismissActivityEventRemovesItemFromFeed() = runBlocking {
        val kept = activityEvent(eventId = "event-1")
        val dismissed = activityEvent(eventId = "event-2")
        val repository = FakeActivityFeedRepository(
            result = AppResult.Success(ActivityFeedUi(items = listOf(kept, dismissed)))
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
    private val results: MutableList<AppResult<ActivityFeedUi>> = mutableListOf()
) : ActivityFeedRepository {
    val loadCalls = mutableListOf<ActivityLoadCall>()
    val dismissCalls = mutableListOf<String>()

    override suspend fun loadActivity(
        tab: ActivityTabUi,
        cursor: String?,
        limit: Int
    ): AppResult<ActivityFeedUi> {
        loadCalls += ActivityLoadCall(tab = tab, cursor = cursor, limit = limit)
        return if (results.isNotEmpty()) {
            results.removeAt(0)
        } else {
            result
        }
    }

    override suspend fun dismissActivityEvent(eventId: String): AppResult<DismissActivityEventResponseDto> {
        dismissCalls += eventId
        return AppResult.Success(
            DismissActivityEventResponseDto(
                eventId = eventId,
                dismissed = true
            )
        )
    }

    override suspend fun respondToAlbumRequest(
        eventId: String,
        approve: Boolean
    ): AppResult<RespondAlbumAccessResponseDto> =
        AppResult.Success(
            RespondAlbumAccessResponseDto(
                eventId = eventId,
                status = if (approve) "approved" else "denied"
            )
        )
}

private data class ActivityLoadCall(
    val tab: ActivityTabUi,
    val cursor: String?,
    val limit: Int
)

private fun activityEvent(
    eventId: String,
    type: String = "profile_view"
): ActivityEventDto =
    ActivityEventDto(
        eventId = eventId,
        type = type,
        actorUserId = "actor-1",
        actorDisplayName = "Maya",
        occurredAt = "2026-05-01T17:30:00Z"
    )
