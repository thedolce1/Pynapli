package com.pynapli.app.media

import com.pynapli.shared.core.result.AppError
import com.pynapli.shared.core.result.AppResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MediaAlbumsStateHolderTest {
    @Test
    fun loadStartsIndependentAlbumRequestsTogether() =
        runBlocking {
            val profileStarted = CompletableDeferred<Unit>()
            val privateStarted = CompletableDeferred<Unit>()
            val grantsStarted = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val repository =
                FakeMediaAlbumsRepository(
                    beforeLoadProfile = {
                        profileStarted.complete(Unit)
                        release.await()
                    },
                    beforeLoadPrivate = {
                        privateStarted.complete(Unit)
                        release.await()
                    },
                    beforeLoadGrants = {
                        grantsStarted.complete(Unit)
                        release.await()
                    },
                )
            val stateHolder = MediaAlbumsStateHolder(repository)

            val load = async { stateHolder.load() }

            withTimeout(1_000) {
                profileStarted.await()
                privateStarted.await()
                grantsStarted.await()
            }
            release.complete(Unit)
            load.await()

            assertEquals(
                "profile-1",
                stateHolder.state.value.profileItems
                    .single()
                    .mediaAssetId,
            )
            assertEquals(
                "private-1",
                stateHolder.state.value.privateItems
                    .single()
                    .mediaAssetId,
            )
            assertEquals(
                "Jordan",
                stateHolder.state.value.privateGrants
                    .single()
                    .displayName,
            )
            assertNull(stateHolder.state.value.errorMessage)
        }
}

private class FakeMediaAlbumsRepository(
    private val beforeLoadProfile: suspend () -> Unit = {},
    private val beforeLoadPrivate: suspend () -> Unit = {},
    private val beforeLoadGrants: suspend () -> Unit = {},
) : MediaAlbumsRepository {
    override suspend fun loadAlbum(kind: MediaAlbumKindUi): AppResult<List<MediaAlbumItemUi>> {
        when (kind) {
            MediaAlbumKindUi.Profile -> beforeLoadProfile()
            MediaAlbumKindUi.Private -> beforeLoadPrivate()
        }
        return AppResult.Success(listOf(testMediaItem(kind)))
    }

    override suspend fun uploadMedia(
        kind: MediaAlbumKindUi,
        media: PickedMediaForUpload,
    ): AppResult<MediaAlbumItemUi> = AppResult.Failure(AppError("UNIMPLEMENTED", "Not implemented."))

    override suspend fun loadPrivateGrants(): AppResult<List<MediaPrivateGrantUi>> {
        beforeLoadGrants()
        return AppResult.Success(
            listOf(
                MediaPrivateGrantUi(
                    granteeUserId = "user-2",
                    displayName = "Jordan",
                    grantedAt = "2026-06-09T12:00:00Z",
                ),
            ),
        )
    }

    override suspend fun revokePrivateGrant(granteeUserId: String): AppResult<Unit> = AppResult.Failure(AppError("UNIMPLEMENTED", "Not implemented."))
}

private fun testMediaItem(kind: MediaAlbumKindUi): MediaAlbumItemUi =
    MediaAlbumItemUi(
        mediaAssetId = "${kind.apiValue}-1",
        albumId = "${kind.apiValue}-album",
        albumKind = kind,
        mediaType = "image",
        contentType = "image/jpeg",
        processingStatus = "ready",
        moderationStatus = "approved",
        sortOrder = 0,
        createdAt = "2026-06-09T12:00:00Z",
        mediaUrl = "https://media.example/${kind.apiValue}-1.jpg",
    )
