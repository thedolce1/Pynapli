package com.pynapli.app.activity

import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.dto.DismissActivityEventResponseDto
import com.pynapli.shared.network.dto.MarkActivitySeenResponseDto
import com.pynapli.shared.network.dto.RespondAlbumAccessResponseDto

interface ActivityFeedRepository {
    suspend fun loadActivity(
        tab: ActivityTabUi,
        cursor: String? = null,
        limit: Int = ActivityFeedUi.DEFAULT_PAGE_SIZE,
    ): AppResult<ActivityFeedUi>

    suspend fun loadSummary(): AppResult<ActivitySummaryUi>

    suspend fun markActivitySeen(eventIds: List<String>): AppResult<MarkActivitySeenResponseDto>

    suspend fun dismissActivityEvent(eventId: String): AppResult<DismissActivityEventResponseDto>

    suspend fun respondToAlbumRequest(
        eventId: String,
        approve: Boolean,
    ): AppResult<RespondAlbumAccessResponseDto>
}
