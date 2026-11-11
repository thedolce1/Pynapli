package com.pynapli.server.safety

import com.pynapli.server.location.TravelLocationRecord
import com.pynapli.server.location.TravelLocationRepository
import com.pynapli.server.location.TravelLocationUpsert
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class TravelRiskEnforcementTest {
    private class RecordingTravel : TravelLocationRepository {
        val cleared = mutableListOf<UUID>()

        override suspend fun findActive(userId: UUID): TravelLocationRecord? = null

        override suspend fun setActive(
            userId: UUID,
            request: TravelLocationUpsert,
            now: Instant,
        ): TravelLocationRecord = error("unused")

        override suspend fun clearActive(
            userId: UUID,
            now: Instant,
        ): Boolean {
            cleared += userId
            return true
        }

        override suspend fun countActivationsSince(
            userId: UUID,
            since: Instant,
        ): Int = 0
    }

    @Test
    fun aRestrictedTierEndsTheActiveTripAndALowerOneDoesNot() =
        runBlocking {
            val travel = RecordingTravel()
            val enforcement = TravelRiskEnforcement(travel)
            val flagged = UUID.randomUUID()

            enforcement.onTierChanged(UUID.randomUUID(), "clean")
            enforcement.onTierChanged(UUID.randomUUID(), "watch")
            enforcement.onTierChanged(flagged, "friction")

            assertEquals(listOf(flagged), travel.cleared)
        }
}
