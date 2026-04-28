package com.pynapli.server.activity

import com.pynapli.server.safety.BlockRepository
import com.pynapli.server.safety.BlockRecord
import com.pynapli.server.safety.BlockSummaryRecord
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ActivityServiceTest {
    @Test
    fun sendFlirtRecordsSparkSignal() = runBlocking {
        val senderUserId = UUID.randomUUID()
        val targetUserId = UUID.randomUUID()
        val repository = InMemoryActivityRepository()
        val service = ActivityService(repository)

        val response = service.sendFlirt(senderUserId, targetUserId)

        assertEquals(true, response.sent)
        assertEquals(listOf(senderUserId to targetUserId), repository.flirts)
    }

    @Test
    fun sendFlirtRejectsSelfTarget() = runBlocking {
        val userId = UUID.randomUUID()
        val service = ActivityService(InMemoryActivityRepository())

        val error = assertFailsWith<ActivityException> {
            service.sendFlirt(userId, userId)
        }

        assertEquals("INVALID_FLIRT_TARGET", error.code)
    }

    @Test
    fun sendFlirtRejectsProfilesThatDoNotAcceptFlirts() = runBlocking {
        val service = ActivityService(InMemoryActivityRepository(allowFlirts = false))

        val error = assertFailsWith<ActivityException> {
            service.sendFlirt(UUID.randomUUID(), UUID.randomUUID())
        }

        assertEquals("FLIRT_NOT_ALLOWED", error.code)
    }

    @Test
    fun requestAlbumAccessRecordsAlbumRequestSignal() = runBlocking {
        val requesterUserId = UUID.randomUUID()
        val targetUserId = UUID.randomUUID()
        val repository = InMemoryActivityRepository()
        val service = ActivityService(repository)

        val response = service.requestAlbumAccess(requesterUserId, targetUserId)

        assertEquals(true, response.requested)
        assertEquals(listOf(requesterUserId to targetUserId), repository.albumRequests)
    }

    @Test
    fun requestAlbumAccessRejectsSelfTarget() = runBlocking {
        val userId = UUID.randomUUID()
        val service = ActivityService(InMemoryActivityRepository())

        val error = assertFailsWith<ActivityException> {
            service.requestAlbumAccess(userId, userId)
        }

        assertEquals("INVALID_ALBUM_REQUEST_TARGET", error.code)
    }

    @Test
    fun requestAlbumAccessRejectsProfilesThatDoNotAcceptAlbumRequests() = runBlocking {
        val service = ActivityService(InMemoryActivityRepository(allowAlbumRequests = false))

        val error = assertFailsWith<ActivityException> {
            service.requestAlbumAccess(UUID.randomUUID(), UUID.randomUUID())
        }

        assertEquals("ALBUM_REQUEST_NOT_ALLOWED", error.code)
    }

    @Test
    fun listActivityFiltersOutEventsFromBlockedRelatedUsers() = runBlocking {
        val viewer = UUID.fromString("00000000-0000-0000-0000-000000000501")
        val blockedActor = UUID.fromString("00000000-0000-0000-0000-000000000502")
        val blockerOfViewer = UUID.fromString("00000000-0000-0000-0000-000000000503")
        val visibleActor = UUID.fromString("00000000-0000-0000-0000-000000000504")

        val seededEvents = listOf(
            sampleEvent(blockedActor, "viewed_profile", "Alex"),
            sampleEvent(blockerOfViewer, "flirt", "Sam"),
            sampleEvent(visibleActor, "viewed_profile", "Maya")
        )
        val service = ActivityService(
            repository = InMemoryActivityRepository(seededEvents = seededEvents),
            blockRepository = StaticBlockRepository(
                relatedByUser = mapOf(viewer to setOf(blockedActor, blockerOfViewer))
            )
        )

        val response = service.listActivity(viewer)

        assertEquals(1, response.items.size)
        assertEquals(visibleActor.toString(), response.items.single().actorUserId)
    }

    @Test
    fun listActivityIsUnchangedWhenNoBlockRepositoryIsAttached() = runBlocking {
        val viewer = UUID.fromString("00000000-0000-0000-0000-000000000601")
        val actor = UUID.fromString("00000000-0000-0000-0000-000000000602")
        val service = ActivityService(
            repository = InMemoryActivityRepository(
                seededEvents = listOf(sampleEvent(actor, "viewed_profile", "Maya"))
            )
        )

        val response = service.listActivity(viewer)

        assertEquals(1, response.items.size)
        assertTrue(response.items.single().actorUserId == actor.toString())
    }
}

private fun sampleEvent(actorUserId: UUID, type: String, displayName: String): ActivityEventRecord =
    ActivityEventRecord(
        eventId = UUID.randomUUID(),
        type = type,
        actorUserId = actorUserId,
        actorDisplayName = displayName,
        actorProfilePhotoMediaAssetId = null,
        count = 1,
        occurredAt = Instant.parse("2026-04-28T12:00:00Z"),
        showDistance = false,
        distanceMeters = null,
        albumRequestStatus = null
    )

private class StaticBlockRepository(
    private val relatedByUser: Map<UUID, Set<UUID>>
) : BlockRepository {
    override suspend fun block(
        blockerUserId: UUID,
        blockedUserId: UUID,
        reason: String?,
        blockIdFactory: () -> UUID
    ): BlockRecord = throw UnsupportedOperationException("not used")

    override suspend fun unblock(blockerUserId: UUID, blockedUserId: UUID): Boolean = false

    override suspend fun listBlocks(blockerUserId: UUID): List<BlockSummaryRecord> = emptyList()

    override suspend fun isBlockedEitherWay(userIdA: UUID, userIdB: UUID): Boolean =
        relatedByUser[userIdA]?.contains(userIdB) == true ||
            relatedByUser[userIdB]?.contains(userIdA) == true

    override suspend fun listBlockedRelatedUserIds(userId: UUID): Set<UUID> =
        relatedByUser[userId].orEmpty()
}

private class InMemoryActivityRepository(
    private val allowFlirts: Boolean = true,
    private val allowAlbumRequests: Boolean = true,
    private val seededEvents: List<ActivityEventRecord> = emptyList()
) : ActivityRepository {
    val flirts = mutableListOf<Pair<UUID, UUID>>()
    val albumRequests = mutableListOf<Pair<UUID, UUID>>()

    override suspend fun recordProfileView(viewerUserId: UUID, targetUserId: UUID) = Unit

    override suspend fun recordFlirt(senderUserId: UUID, targetUserId: UUID): Boolean {
        if (!allowFlirts) return false
        flirts += senderUserId to targetUserId
        return true
    }

    override suspend fun recordAlbumRequest(requesterUserId: UUID, targetUserId: UUID): Boolean {
        if (!allowAlbumRequests) return false
        albumRequests += requesterUserId to targetUserId
        return true
    }

    override suspend fun getAlbumRequestEvent(recipientUserId: UUID, eventId: UUID): AlbumRequestEventRecord? = null

    override suspend fun listActivity(recipientUserId: UUID, limit: Int): List<ActivityEventRecord> =
        seededEvents.take(limit)
}
