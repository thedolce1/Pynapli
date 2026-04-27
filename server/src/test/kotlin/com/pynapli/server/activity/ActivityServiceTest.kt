package com.pynapli.server.activity

import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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
}

private class InMemoryActivityRepository(
    private val allowFlirts: Boolean = true,
    private val allowAlbumRequests: Boolean = true
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

    override suspend fun listActivity(recipientUserId: UUID, limit: Int): List<ActivityEventRecord> = emptyList()
}
