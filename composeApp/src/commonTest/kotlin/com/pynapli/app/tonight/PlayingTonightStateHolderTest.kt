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
}
