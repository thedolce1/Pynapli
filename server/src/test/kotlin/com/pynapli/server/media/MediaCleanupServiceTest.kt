package com.pynapli.server.media

import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

class MediaCleanupServiceTest {
    @Test
    fun purgeComputesCutoffsFromClockAndRetentionAndSumsTotals() =
        runBlocking {
            val now = Instant.parse("2026-05-31T12:00:00Z")
            val repository = RecordingMediaCleanupRepository(MediaCleanupSummary(abandonedUploads = 2, rejectedAssets = 1, softDeletedAssets = 3))
            val service =
                MediaCleanupService(
                    repository = repository,
                    abandonedUploadGrace = Duration.ofHours(24),
                    rejectedRetention = Duration.ofDays(7),
                    softDeletedRetention = Duration.ofDays(30),
                    clock = Clock.fixed(now, ZoneOffset.UTC),
                )

            val summary = service.purgeStaleAssets()

            assertEquals(6, summary.total)
            assertEquals(now.minus(Duration.ofHours(24)), repository.abandonedBefore)
            assertEquals(now.minus(Duration.ofDays(7)), repository.rejectedBefore)
            assertEquals(now.minus(Duration.ofDays(30)), repository.softDeletedBefore)
        }
}

private class RecordingMediaCleanupRepository(
    private val result: MediaCleanupSummary,
) : MediaCleanupRepository {
    var abandonedBefore: Instant? = null
    var rejectedBefore: Instant? = null
    var softDeletedBefore: Instant? = null

    override suspend fun purgeStaleAssets(
        abandonedBefore: Instant,
        rejectedBefore: Instant,
        softDeletedBefore: Instant,
    ): MediaCleanupSummary {
        this.abandonedBefore = abandonedBefore
        this.rejectedBefore = rejectedBefore
        this.softDeletedBefore = softDeletedBefore
        return result
    }
}
