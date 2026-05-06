package com.pynapli.app.activity

import com.pynapli.shared.core.result.AppError
import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.dto.ActivityEventDto
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
        assertEquals(true, state.adSupported)
        assertEquals("free", state.entitlementTier)
        assertEquals(1, state.revealLimit)
        assertEquals(4, state.gatedCount)
    }
}

private class FakeActivityFeedRepository(
    var result: AppResult<ActivityFeedUi>
) : ActivityFeedRepository {
    override suspend fun loadActivity(): AppResult<ActivityFeedUi> = result

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

private fun activityEvent(eventId: String): ActivityEventDto =
    ActivityEventDto(
        eventId = eventId,
        type = "profile_view",
        actorUserId = "actor-1",
        actorDisplayName = "Maya",
        occurredAt = "2026-05-01T17:30:00Z"
    )
