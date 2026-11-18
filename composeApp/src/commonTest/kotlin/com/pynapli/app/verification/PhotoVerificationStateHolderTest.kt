package com.pynapli.app.verification

import com.pynapli.app.media.PickedMediaForUpload
import com.pynapli.shared.core.result.AppError
import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.dto.PhotoVerificationChallengeDto
import com.pynapli.shared.network.dto.PhotoVerificationStatus
import com.pynapli.shared.network.dto.PhotoVerificationStatusDto
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PhotoVerificationStateHolderTest {
    @Test
    fun openingLoadsTheStatusWithoutRequestingAChallenge() =
        runTest {
            val repository = FakePhotoVerificationRepository(status = AppResult.Success(none()))
            val holder = PhotoVerificationStateHolder(repository)

            holder.open()
            holder.load()

            assertTrue(holder.state.value.sheetVisible)
            assertEquals(PhotoVerificationStatus.NONE, holder.state.value.status)
            assertEquals(0, repository.challengeRequests)
            assertTrue(holder.state.value.canRequestChallenge)
        }

    @Test
    fun anExplicitTapRequestsAChallengeAndShowsThePose() =
        runTest {
            val repository =
                FakePhotoVerificationRepository(
                    status = AppResult.Success(none()),
                    challenge = AppResult.Success(challenge()),
                )
            val holder = PhotoVerificationStateHolder(repository)

            holder.requestChallenge()

            val state = holder.state.value
            assertEquals(1, repository.challengeRequests)
            assertEquals(PhotoVerificationStatus.CHALLENGE, state.status)
            assertEquals("Touch your ear with one hand.", state.poseInstruction)
            assertTrue(state.canTakeSelfie)
        }

    @Test
    fun submittingTheSelfieMovesToPending() =
        runTest {
            val repository =
                FakePhotoVerificationRepository(
                    status = AppResult.Success(challenge()),
                    submit = AppResult.Success(PhotoVerificationStatusDto(status = PhotoVerificationStatus.PENDING, submittedAt = "2026-11-17T10:01:00Z")),
                )
            val holder = PhotoVerificationStateHolder(repository)
            holder.load()

            holder.submitSelfie(selfie())

            assertEquals(listOf("c-1"), repository.submittedChallengeIds)
            assertEquals(PhotoVerificationStatus.PENDING, holder.state.value.status)
            assertEquals(false, holder.state.value.isSubmitting)
        }

    @Test
    fun aFailedSubmitRefetchesTheStatusInsteadOfRetrying() =
        runTest {
            val repository =
                FakePhotoVerificationRepository(
                    status = AppResult.Success(challenge()),
                    submit = AppResult.Failure(AppError("NETWORK", "Connection lost.")),
                )
            val holder = PhotoVerificationStateHolder(repository)
            holder.load()
            // The submit reached the server before the response was lost: it is pending now.
            repository.status = AppResult.Success(PhotoVerificationStatusDto(status = PhotoVerificationStatus.PENDING))

            holder.submitSelfie(selfie())

            assertEquals(1, repository.submittedChallengeIds.size)
            assertEquals(2, repository.statusLoads)
            assertEquals(PhotoVerificationStatus.PENDING, holder.state.value.status)
            assertEquals("Connection lost.", holder.state.value.errorMessage)
        }

    @Test
    fun aSelfieWithoutAnOpenPoseIsNotSent() =
        runTest {
            val repository = FakePhotoVerificationRepository(status = AppResult.Success(none()))
            val holder = PhotoVerificationStateHolder(repository)
            holder.load()

            holder.submitSelfie(selfie())

            assertTrue(repository.submittedChallengeIds.isEmpty())
            assertEquals("Get your pose first, then take the selfie.", holder.state.value.errorMessage)
        }

    @Test
    fun rejectionShowsTheReasonAndWhenToRetry() =
        runTest {
            val repository =
                FakePhotoVerificationRepository(
                    status =
                        AppResult.Success(
                            PhotoVerificationStatusDto(
                                status = PhotoVerificationStatus.REJECTED,
                                rejectionReason = "Your face is not visible.",
                                retryAvailableAt = "2026-11-17T12:00:00Z",
                            ),
                        ),
                )
            val holder = PhotoVerificationStateHolder(repository, nowMillis = { 1_794_916_800_000 - 30 * 60_000 })

            holder.load()

            val state = holder.state.value
            assertEquals("Your face is not visible.", state.rejectionReason)
            assertEquals("2026-11-17T12:00:00Z", state.retryAvailableAt)
            assertTrue(state.canRequestChallenge)
        }

    @Test
    fun resetForgetsThePreviousAccount() =
        runTest {
            val repository = FakePhotoVerificationRepository(status = AppResult.Success(PhotoVerificationStatusDto(status = PhotoVerificationStatus.VERIFIED)))
            val holder = PhotoVerificationStateHolder(repository)
            holder.load()

            holder.reset()

            assertEquals(PhotoVerificationStatus.NONE, holder.state.value.status)
            assertNull(holder.state.value.poseInstruction)
            assertEquals(false, holder.state.value.loaded)
        }

    @Test
    fun retryLabelCountsDownToTheRetryTime() {
        val retryAt = "2026-11-17T12:00:00Z"
        val retryMillis = 1_794_916_800_000L // 2026-11-17T12:00:00Z
        assertEquals("You can try again now.", photoVerificationRetryLabel(retryAt, nowMillis = retryMillis))
        assertEquals("You can try again in 25 min.", photoVerificationRetryLabel(retryAt, nowMillis = retryMillis - 25 * 60_000))
        assertEquals("You can try again in 3 h.", photoVerificationRetryLabel(retryAt, nowMillis = retryMillis - 3 * 60 * 60_000))
        assertNull(photoVerificationRetryLabel(null, nowMillis = retryMillis))
    }

    private fun none() = PhotoVerificationStatusDto(status = PhotoVerificationStatus.NONE)

    private fun challenge() =
        PhotoVerificationStatusDto(
            status = PhotoVerificationStatus.CHALLENGE,
            challenge =
                PhotoVerificationChallengeDto(
                    challengeId = "c-1",
                    poseKey = "touch_ear",
                    instruction = "Touch your ear with one hand.",
                    expiresAt = "2026-11-17T10:10:00Z",
                ),
        )

    private fun selfie() =
        PickedMediaForUpload(
            contentType = "image/jpeg",
            byteSize = 3,
            pixelWidth = 1,
            pixelHeight = 1,
            bytes = byteArrayOf(1, 2, 3),
        )
}

private class FakePhotoVerificationRepository(
    var status: AppResult<PhotoVerificationStatusDto>,
    var challenge: AppResult<PhotoVerificationStatusDto> = AppResult.Failure(AppError("UNUSED", "unused")),
    var submit: AppResult<PhotoVerificationStatusDto> = AppResult.Failure(AppError("UNUSED", "unused")),
) : PhotoVerificationRepository {
    var challengeRequests = 0
    var statusLoads = 0
    val submittedChallengeIds = mutableListOf<String>()

    override suspend fun loadStatus(): AppResult<PhotoVerificationStatusDto> {
        statusLoads += 1
        return status
    }

    override suspend fun requestChallenge(): AppResult<PhotoVerificationStatusDto> {
        challengeRequests += 1
        return challenge
    }

    override suspend fun submitSelfie(
        challengeId: String,
        media: PickedMediaForUpload,
    ): AppResult<PhotoVerificationStatusDto> {
        submittedChallengeIds += challengeId
        return submit
    }
}
