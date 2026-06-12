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
import kotlin.test.assertSame
import kotlin.test.assertTrue

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

    @Test
    fun deleteRemovesItemFromAlbumOnSuccess() =
        runBlocking {
            val items = listOf(testMediaItem(MediaAlbumKindUi.Profile, "asset-a", 0), testMediaItem(MediaAlbumKindUi.Profile, "asset-b", 1))
            val repository = FakeMediaAlbumsRepository(profileItems = items)
            val stateHolder = MediaAlbumsStateHolder(repository)
            stateHolder.load()

            stateHolder.deleteMedia(MediaAlbumKindUi.Profile, "asset-a")

            assertEquals(listOf("asset-a"), repository.deletedAssetIds)
            assertEquals(
                listOf("asset-b"),
                stateHolder.state.value.profileItems
                    .map { it.mediaAssetId },
            )
            assertTrue(
                stateHolder.state.value.busyAssetIds
                    .isEmpty(),
            )
            assertNull(stateHolder.state.value.errorMessage)
        }

    @Test
    fun deleteFailureKeepsItemAndSurfacesError() =
        runBlocking {
            val items = listOf(testMediaItem(MediaAlbumKindUi.Profile, "asset-a", 0))
            val repository =
                FakeMediaAlbumsRepository(profileItems = items).apply {
                    deleteResult = AppResult.Failure(AppError("MEDIA_DELETE_FAILED", "Could not delete."))
                }
            val stateHolder = MediaAlbumsStateHolder(repository)
            stateHolder.load()

            stateHolder.deleteMedia(MediaAlbumKindUi.Profile, "asset-a")

            assertEquals(
                listOf("asset-a"),
                stateHolder.state.value.profileItems
                    .map { it.mediaAssetId },
            )
            assertTrue(
                stateHolder.state.value.busyAssetIds
                    .isEmpty(),
            )
            assertEquals("Could not delete.", stateHolder.state.value.errorMessage)
        }

    @Test
    fun moveLaterSubmitsExactOrderAndAdoptsServerResponse() =
        runBlocking {
            val items =
                listOf(
                    testMediaItem(MediaAlbumKindUi.Profile, "asset-a", 0),
                    testMediaItem(MediaAlbumKindUi.Profile, "asset-b", 1),
                    testMediaItem(MediaAlbumKindUi.Profile, "asset-c", 2),
                )
            val repository = FakeMediaAlbumsRepository(profileItems = items)
            val stateHolder = MediaAlbumsStateHolder(repository)
            stateHolder.load()

            stateHolder.moveMedia(MediaAlbumKindUi.Profile, "asset-a", MediaMoveDirection.Down)

            val request = repository.reorderRequests.single()
            assertEquals(MediaAlbumKindUi.Profile, request.first)
            assertEquals(listOf("asset-b", "asset-a", "asset-c"), request.second)
            // The fake echoes the canonical server-sorted listing; the state must adopt it.
            assertEquals(
                listOf("asset-b", "asset-a", "asset-c"),
                stateHolder.state.value.profileItems
                    .map { it.mediaAssetId },
            )
            assertTrue(
                stateHolder.state.value.busyAssetIds
                    .isEmpty(),
            )
            assertNull(stateHolder.state.value.errorMessage)
        }

    @Test
    fun moveAtAlbumEdgeIsNoOp() =
        runBlocking {
            val items =
                listOf(
                    testMediaItem(MediaAlbumKindUi.Profile, "asset-a", 0),
                    testMediaItem(MediaAlbumKindUi.Profile, "asset-b", 1),
                )
            val repository = FakeMediaAlbumsRepository(profileItems = items)
            val stateHolder = MediaAlbumsStateHolder(repository)
            stateHolder.load()

            stateHolder.moveMedia(MediaAlbumKindUi.Profile, "asset-a", MediaMoveDirection.Up)
            stateHolder.moveMedia(MediaAlbumKindUi.Profile, "asset-b", MediaMoveDirection.Down)

            assertTrue(repository.reorderRequests.isEmpty())
            assertEquals(
                listOf("asset-a", "asset-b"),
                stateHolder.state.value.profileItems
                    .map { it.mediaAssetId },
            )
        }

    @Test
    fun moveFailureKeepsOrderAndSurfacesError() =
        runBlocking {
            val items =
                listOf(
                    testMediaItem(MediaAlbumKindUi.Profile, "asset-a", 0),
                    testMediaItem(MediaAlbumKindUi.Profile, "asset-b", 1),
                )
            val repository =
                FakeMediaAlbumsRepository(profileItems = items).apply {
                    reorderResult = AppResult.Failure(AppError("MEDIA_ALBUM_ORDER_MISMATCH", "Order mismatch."))
                }
            val stateHolder = MediaAlbumsStateHolder(repository)
            stateHolder.load()

            stateHolder.moveMedia(MediaAlbumKindUi.Profile, "asset-a", MediaMoveDirection.Down)

            assertEquals(
                listOf("asset-a", "asset-b"),
                stateHolder.state.value.profileItems
                    .map { it.mediaAssetId },
            )
            assertTrue(
                stateHolder.state.value.busyAssetIds
                    .isEmpty(),
            )
            assertEquals("Order mismatch.", stateHolder.state.value.errorMessage)
        }

    @Test
    fun failedUploadIsRetainedAndRetrySucceedsWithSameMedia() =
        runBlocking {
            val repository =
                FakeMediaAlbumsRepository().apply {
                    uploadResults += AppResult.Failure(AppError("MEDIA_UPLOAD_FAILED", "Upload failed."))
                    uploadResults += AppResult.Success(testMediaItem(MediaAlbumKindUi.Profile, "asset-new", 0))
                }
            val stateHolder = MediaAlbumsStateHolder(repository)
            val media =
                PickedMediaForUpload(
                    contentType = "image/jpeg",
                    byteSize = 3,
                    pixelWidth = 1,
                    pixelHeight = 1,
                    bytes = byteArrayOf(1, 2, 3),
                )

            stateHolder.uploadPickedMedia(MediaAlbumKindUi.Profile, media)

            assertEquals(setOf(MediaAlbumKindUi.Profile), stateHolder.state.value.retryableUploadKinds)
            assertEquals("Upload failed.", stateHolder.state.value.errorMessage)

            stateHolder.retryUpload(MediaAlbumKindUi.Profile)

            assertEquals(2, repository.uploadRequests.size)
            assertSame(media, repository.uploadRequests.last().second)
            assertTrue(
                stateHolder.state.value.retryableUploadKinds
                    .isEmpty(),
            )
            assertEquals(
                listOf("asset-new"),
                stateHolder.state.value.profileItems
                    .map { it.mediaAssetId },
            )
            assertNull(stateHolder.state.value.errorMessage)

            // A retry with nothing retained is a no-op rather than a crash or duplicate call.
            stateHolder.retryUpload(MediaAlbumKindUi.Profile)
            assertEquals(2, repository.uploadRequests.size)
        }
}

private class FakeMediaAlbumsRepository(
    private val profileItems: List<MediaAlbumItemUi> = listOf(testMediaItem(MediaAlbumKindUi.Profile, "profile-1", 0)),
    private val privateItems: List<MediaAlbumItemUi> = listOf(testMediaItem(MediaAlbumKindUi.Private, "private-1", 0)),
    private val beforeLoadProfile: suspend () -> Unit = {},
    private val beforeLoadPrivate: suspend () -> Unit = {},
    private val beforeLoadGrants: suspend () -> Unit = {},
) : MediaAlbumsRepository {
    var deleteResult: AppResult<Unit> = AppResult.Success(Unit)
    val deletedAssetIds = mutableListOf<String>()

    /** When null the fake echoes the requested order back as the canonical listing. */
    var reorderResult: AppResult<List<MediaAlbumItemUi>>? = null
    val reorderRequests = mutableListOf<Pair<MediaAlbumKindUi, List<String>>>()

    val uploadResults = ArrayDeque<AppResult<MediaAlbumItemUi>>()
    val uploadRequests = mutableListOf<Pair<MediaAlbumKindUi, PickedMediaForUpload>>()

    override suspend fun loadAlbum(kind: MediaAlbumKindUi): AppResult<List<MediaAlbumItemUi>> {
        when (kind) {
            MediaAlbumKindUi.Profile -> beforeLoadProfile()
            MediaAlbumKindUi.Private -> beforeLoadPrivate()
        }
        return AppResult.Success(
            when (kind) {
                MediaAlbumKindUi.Profile -> profileItems
                MediaAlbumKindUi.Private -> privateItems
            },
        )
    }

    override suspend fun deleteMedia(assetId: String): AppResult<Unit> {
        deletedAssetIds += assetId
        return deleteResult
    }

    override suspend fun reorderAlbum(
        kind: MediaAlbumKindUi,
        orderedAssetIds: List<String>,
    ): AppResult<List<MediaAlbumItemUi>> {
        reorderRequests += kind to orderedAssetIds
        reorderResult?.let { return it }
        val byId =
            when (kind) {
                MediaAlbumKindUi.Profile -> profileItems
                MediaAlbumKindUi.Private -> privateItems
            }.associateBy { it.mediaAssetId }
        return AppResult.Success(
            orderedAssetIds
                .mapNotNull(byId::get)
                .mapIndexed { index, item -> item.copy(sortOrder = index) },
        )
    }

    override suspend fun uploadMedia(
        kind: MediaAlbumKindUi,
        media: PickedMediaForUpload,
    ): AppResult<MediaAlbumItemUi> {
        uploadRequests += kind to media
        return uploadResults.removeFirstOrNull() ?: AppResult.Failure(AppError("UNIMPLEMENTED", "Not implemented."))
    }

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

private fun testMediaItem(
    kind: MediaAlbumKindUi,
    assetId: String,
    sortOrder: Int,
): MediaAlbumItemUi =
    MediaAlbumItemUi(
        mediaAssetId = assetId,
        albumId = "${kind.apiValue}-album",
        albumKind = kind,
        mediaType = "image",
        contentType = "image/jpeg",
        processingStatus = "ready",
        moderationStatus = "approved",
        sortOrder = sortOrder,
        createdAt = "2026-06-09T12:00:00Z",
        mediaUrl = "https://media.example/$assetId.jpg",
    )
