package com.pynapli.server.profile

import com.pynapli.server.activity.ActivityService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.slf4j.LoggerFactory
import java.util.UUID

internal class ProfileViewActivityRecorder(
    private val activityService: ActivityService?,
    private val scope: CoroutineScope?,
    concurrency: Int,
) {
    private val semaphore = scope?.let { Semaphore(concurrency.coerceAtLeast(1)) }

    suspend fun record(
        viewerUserId: UUID,
        targetUserId: UUID,
    ) {
        val service = activityService ?: return
        val recordScope = scope
        if (recordScope == null) {
            recordInline(service, viewerUserId, targetUserId)
            return
        }

        recordScope.launch {
            val permit = semaphore
            if (permit == null) {
                recordInline(service, viewerUserId, targetUserId)
            } else {
                permit.withPermit {
                    recordInline(service, viewerUserId, targetUserId)
                }
            }
        }
    }

    private suspend fun recordInline(
        service: ActivityService,
        viewerUserId: UUID,
        targetUserId: UUID,
    ) {
        try {
            service.recordProfileView(viewerUserId, targetUserId)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            logger.warn(
                "Failed to record profile-view activity for viewer {} target {}",
                viewerUserId,
                targetUserId,
                error,
            )
        }
    }

    private companion object {
        val logger = LoggerFactory.getLogger(ProfileViewActivityRecorder::class.java)
    }
}
