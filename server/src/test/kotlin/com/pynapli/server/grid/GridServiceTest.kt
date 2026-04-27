package com.pynapli.server.grid

import com.pynapli.server.presence.PresenceRepository
import com.pynapli.server.presence.PresenceService
import com.pynapli.server.presence.PresenceState
import com.pynapli.server.media.MediaAssetUrlResolver
import com.pynapli.shared.network.dto.GridQueryRequestDto
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class GridServiceTest {
    @Test
    fun queryGridRequiresViewerLocation() = runBlocking {
        val service = GridService(
            StaticGridRepository(GridQueryResult(viewerHadLocation = false, cards = emptyList()))
        )

        val error = assertFailsWith<GridException> {
            service.queryGrid(UUID.randomUUID(), GridQueryRequestDto())
        }

        assertEquals("LOCATION_REQUIRED", error.code)
    }

    @Test
    fun queryGridReturnsMinimalCards() = runBlocking {
        val targetId = UUID.randomUUID()
        val service = GridService(
            StaticGridRepository(
                GridQueryResult(
                    viewerHadLocation = true,
                    cards = listOf(
                        GridCardRecord(
                            userId = targetId,
                            displayName = "Alex",
                            showDistance = true,
                            distanceMeters = 1_240.0
                        )
                    )
                )
            )
        )

        val response = service.queryGrid(UUID.randomUUID(), GridQueryRequestDto())

        assertEquals(1, response.items.size)
        assertEquals(targetId.toString(), response.items.first().userId)
        assertEquals("Alex", response.items.first().displayName)
        assertEquals("1.2 km", response.items.first().distanceLabel)
        assertEquals(false, response.items.first().online)
        assertNull(response.items.first().thumbnailUrl)
    }

    @Test
    fun queryGridHidesDistanceWhenTargetDoesNotShowDistance() = runBlocking {
        val service = GridService(
            StaticGridRepository(
                GridQueryResult(
                    viewerHadLocation = true,
                    cards = listOf(
                        GridCardRecord(
                            userId = UUID.randomUUID(),
                            displayName = "Alex",
                            showDistance = false,
                            distanceMeters = 300.0
                        )
                    )
                )
            )
        )

        val response = service.queryGrid(UUID.randomUUID(), GridQueryRequestDto())

        assertNull(response.items.first().distanceLabel)
    }

    @Test
    fun queryGridUsesPresenceForOnlineIndicator() = runBlocking {
        val viewerId = UUID.randomUUID()
        val targetId = UUID.randomUUID()
        val service = GridService(
            repository = StaticGridRepository(
                GridQueryResult(
                    viewerHadLocation = true,
                    cards = listOf(
                        GridCardRecord(
                            userId = targetId,
                            displayName = "Alex",
                            showDistance = true,
                            distanceMeters = 300.0
                        )
                    )
                )
            ),
            presenceService = PresenceService(StaticPresenceRepository(setOf(targetId)))
        )

        val response = service.queryGrid(viewerId, GridQueryRequestDto())

        assertEquals(true, response.items.first().online)
    }

    @Test
    fun queryGridIncludesApprovedProfileThumbnailUrlWhenProjected() = runBlocking {
        val targetId = UUID.randomUUID()
        val mediaAssetId = UUID.randomUUID()
        val service = GridService(
            repository = StaticGridRepository(
                GridQueryResult(
                    viewerHadLocation = true,
                    cards = listOf(
                        GridCardRecord(
                            userId = targetId,
                            displayName = "Alex",
                            thumbnailMediaAssetId = mediaAssetId,
                            showDistance = true,
                            distanceMeters = 300.0
                        )
                    )
                )
            ),
            mediaAssetUrlResolver = StaticMediaAssetUrlResolver()
        )

        val response = service.queryGrid(UUID.randomUUID(), GridQueryRequestDto())

        assertEquals("https://media.example/$mediaAssetId", response.items.first().thumbnailUrl)
    }
}

private class StaticGridRepository(
    private val result: GridQueryResult
) : GridRepository {
    override suspend fun queryNearby(viewerUserId: UUID, radiusMeters: Int, limit: Int): GridQueryResult = result
}

private class StaticPresenceRepository(
    private val onlineIds: Set<UUID>
) : PresenceRepository {
    override suspend fun heartbeat(userId: UUID): PresenceState = PresenceState(online = true, expiresInSeconds = 300)

    override suspend fun onlineUserIds(userIds: Collection<UUID>): Set<UUID> =
        userIds.filterTo(mutableSetOf()) { it in onlineIds }
}

private class StaticMediaAssetUrlResolver : MediaAssetUrlResolver {
    override fun publicContentUrl(mediaAssetId: UUID): String = "https://media.example/$mediaAssetId"
}
