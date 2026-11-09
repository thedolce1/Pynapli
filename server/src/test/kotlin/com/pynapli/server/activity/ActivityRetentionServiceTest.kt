package com.pynapli.server.activity

import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

class ActivityRetentionServiceTest {
    @Test
    fun applyProductVisibleRetentionUsesClockAndRepositorySummary() =
        runBlocking {
            val now = Instant.parse("2026-05-20T16:00:00Z")
            val repository =
                RecordingActivityRetentionRepository(
                    summary =
                        ActivityRetentionSummary(
                            hiddenTotal = 3,
                            hiddenByType = mapOf("profile_view" to 2, "flirt" to 1),
                        ),
                )
            val service =
                ActivityRetentionService(
                    repository = repository,
                    clock = Clock.fixed(now, ZoneOffset.UTC),
                )

            val summary = service.applyProductVisibleRetention()

            assertEquals(now, repository.nowSeen)
            assertEquals(3, summary.hiddenTotal)
            assertEquals(mapOf("profile_view" to 2, "flirt" to 1), summary.hiddenByType)
        }
}

private class RecordingActivityRetentionRepository(
    private val summary: ActivityRetentionSummary,
) : ActivityRetentionRepository {
    var nowSeen: Instant? = null

    override suspend fun hideExpiredProductVisibleEvents(now: Instant): ActivityRetentionSummary {
        nowSeen = now
        return summary
    }
}
