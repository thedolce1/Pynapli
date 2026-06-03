package com.pynapli.app.tonight

import com.pynapli.shared.core.result.AppResult
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakeTonightRepository(
    private val feed: List<TonightFeedItemUi>,
    private val current: TonightSessionUi?,
) : TonightRepository {
    var lastIntent: String? = "<unset>"
    var endedSessionId: String? = null

    override suspend fun loadFeed(
        radiusMeters: Int,
        intent: String?,
    ): AppResult<List<TonightFeedItemUi>> {
        lastIntent = intent
        return AppResult.Success(if (intent == null) feed else feed.filter { it.intentRaw == intent })
    }

    override suspend fun loadCurrentSession(): AppResult<TonightSessionUi?> = AppResult.Success(current)

    var photoOptions: List<TonightPhotoOptionUi> = emptyList()

    override suspend fun loadProfilePhotos(): AppResult<List<TonightPhotoOptionUi>> = AppResult.Success(photoOptions)

    var proposed: com.pynapli.shared.network.dto.ProposeTonightSessionRequestDto? = null
    var approvedId: String? = null
    var declinedId: String? = null
    var proposeResult: TonightSessionUi? = null
    var proposeError: com.pynapli.shared.core.result.AppError? = null

    override suspend fun propose(
        request: com.pynapli.shared.network.dto.ProposeTonightSessionRequestDto,
    ): AppResult<TonightSessionUi> {
        proposed = request
        proposeError?.let { return AppResult.Failure(it) }
        return AppResult.Success(proposeResult ?: current!!)
    }

    override suspend fun approve(sessionId: String): AppResult<TonightSessionUi> {
        approvedId = sessionId
        return AppResult.Success(current!!.copy(status = "active"))
    }

    override suspend fun decline(sessionId: String): AppResult<TonightSessionUi> {
        declinedId = sessionId
        return AppResult.Success(current!!.copy(status = "declined"))
    }

    override suspend fun endSession(sessionId: String): AppResult<TonightSessionUi> {
        endedSessionId = sessionId
        return AppResult.Success(current!!)
    }
}

private fun item(
    id: String,
    intent: String,
): TonightFeedItemUi =
    TonightFeedItemUi(
        sessionId = id,
        userId = "user-$id",
        displayName = "User $id",
        thumbnailUrl = null,
        online = true,
        intent = TonightIntent.fromServer(intent),
        intentRaw = intent,
        hostMode = "public_meet_first",
        note = null,
        photoUrl = null,
        distanceLabel = "800 m",
        distanceMeters = 800,
        zoneKey = "10_20",
        linkedPartner = null,
    )

class PlayingTonightStateHolderTest {
    @Test
    fun loadPopulatesFeedAndCurrentSession() =
        runBlocking {
            val session =
                TonightSessionUi(
                    id = "s1",
                    mode = "solo",
                    intent = TonightIntent.Solo,
                    intentRaw = "solo",
                    hostMode = "hosting",
                    status = "active",
                    expiresAt = "2026-06-03T00:00:00Z",
                )
            val holder =
                PlayingTonightStateHolder(
                    FakeTonightRepository(feed = listOf(item("a", "solo"), item("b", "social")), current = session),
                )

            holder.load()

            val state = holder.state.value
            assertEquals(2, state.feedItems.size)
            assertEquals("s1", state.currentSession?.id)
            assertTrue(!state.isLoading)
        }

    @Test
    fun setIntentFilterReQueriesWithIntent() =
        runBlocking {
            val repo = FakeTonightRepository(feed = listOf(item("a", "solo"), item("b", "social")), current = null)
            val holder = PlayingTonightStateHolder(repo)
            holder.load()

            holder.setIntentFilter(TonightIntent.Social)

            assertEquals("social", repo.lastIntent)
            assertEquals(TonightIntent.Social, holder.state.value.intentFilter)
            assertEquals(
                listOf("b"),
                holder.state.value.feedItems
                    .map { it.sessionId },
            )
        }

    @Test
    fun endCurrentSessionClearsTheFloater() =
        runBlocking {
            val session =
                TonightSessionUi(
                    id = "s9",
                    mode = "together",
                    intent = TonightIntent.Together,
                    intentRaw = "together",
                    hostMode = "traveling",
                    status = "active",
                    expiresAt = "2026-06-03T00:00:00Z",
                )
            val repo = FakeTonightRepository(feed = emptyList(), current = session)
            val holder = PlayingTonightStateHolder(repo)
            holder.load()

            holder.endCurrentSession()

            assertEquals("s9", repo.endedSessionId)
            assertNull(holder.state.value.currentSession)
        }

    @Test
    fun proposeSetsCurrentSession() =
        runBlocking {
            val repo = FakeTonightRepository(feed = emptyList(), current = null)
            repo.proposeResult =
                TonightSessionUi(
                    id = "new",
                    mode = "solo",
                    intent = TonightIntent.Solo,
                    intentRaw = "solo",
                    hostMode = "hosting",
                    status = "active",
                    expiresAt = "2026-06-03T00:00:00Z",
                )
            val holder = PlayingTonightStateHolder(repo)

            holder.propose(
                com.pynapli.shared.network.dto
                    .ProposeTonightSessionRequestDto(mode = "solo", intent = "solo", hostMode = "hosting"),
            )

            assertEquals("solo", repo.proposed?.mode)
            assertEquals(
                "new",
                holder.state.value.currentSession
                    ?.id,
            )
        }

    @Test
    fun declineClearsTheProposedSession() =
        runBlocking {
            val proposed =
                TonightSessionUi(
                    id = "p1",
                    mode = "together",
                    intent = TonightIntent.Together,
                    intentRaw = "together",
                    hostMode = "hosting",
                    status = "proposed",
                    expiresAt = "2026-06-03T00:00:00Z",
                )
            val repo = FakeTonightRepository(feed = emptyList(), current = proposed)
            val holder = PlayingTonightStateHolder(repo)
            holder.load()

            holder.declineSession()

            assertEquals("p1", repo.declinedId)
            assertNull(holder.state.value.currentSession)
        }

    @Test
    fun loadPopulatesProfilePhotoOptions() =
        runBlocking {
            val repo = FakeTonightRepository(feed = emptyList(), current = null)
            repo.photoOptions =
                listOf(
                    TonightPhotoOptionUi(assetId = "a1", thumbnailUrl = "https://media.example/a1"),
                    TonightPhotoOptionUi(assetId = "a2", thumbnailUrl = "https://media.example/a2"),
                )
            val holder = PlayingTonightStateHolder(repo)

            holder.load()

            assertEquals(
                listOf("a1", "a2"),
                holder.state.value.photoOptions
                    .map { it.assetId },
            )
        }

    @Test
    fun weeklyLimitSurfacesAnUpgradeMessage() =
        runBlocking {
            val repo = FakeTonightRepository(feed = emptyList(), current = null)
            repo.proposeError =
                com.pynapli.shared.core.result
                    .AppError("TONIGHT_WEEKLY_LIMIT_REACHED", "limit")
            val holder = PlayingTonightStateHolder(repo)

            holder.propose(
                com.pynapli.shared.network.dto
                    .ProposeTonightSessionRequestDto(mode = "solo", intent = "solo", hostMode = "hosting"),
            )

            assertTrue(
                holder.state.value.errorMessage
                    ?.contains("Upgrade", ignoreCase = true) == true,
            )
            assertNull(holder.state.value.currentSession)
        }
}
