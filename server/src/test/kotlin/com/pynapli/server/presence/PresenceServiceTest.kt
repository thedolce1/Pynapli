package com.pynapli.server.presence

import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PresenceServiceTest {
    @Test
    fun heartbeatMarksUserOnline() =
        runBlocking {
            val repository = InMemoryPresenceRepository()
            val service = PresenceService(repository)
            val userId = UUID.randomUUID()

            val response = service.heartbeat(userId)

            assertTrue(response.online)
            assertEquals(300, response.expiresInSeconds)
            assertEquals(setOf(userId), service.onlineUserIds(listOf(userId)))
        }
}

private class InMemoryPresenceRepository : PresenceRepository {
    private val onlineUserIds = mutableSetOf<UUID>()

    override suspend fun heartbeat(userId: UUID): PresenceState {
        onlineUserIds += userId
        return PresenceState(online = true, expiresInSeconds = 300)
    }

    override suspend fun onlineUserIds(userIds: Collection<UUID>): Set<UUID> = userIds.filterTo(mutableSetOf()) { it in onlineUserIds }
}
