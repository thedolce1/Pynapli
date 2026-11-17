package com.pynapli.server.presence

import kotlinx.coroutines.runBlocking
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class PresenceServiceTest {
    @Test
    fun refreshOnlineMarksUserOnline() =
        runBlocking {
            val repository = InMemoryPresenceRepository()
            val service = PresenceService(repository)
            val userId = UUID.randomUUID()

            service.refreshOnline(userId)

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
