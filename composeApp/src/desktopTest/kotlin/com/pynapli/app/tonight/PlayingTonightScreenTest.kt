package com.pynapli.app.tonight

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.pynapli.shared.core.result.AppResult
import kotlin.test.Test

private class StaticTonightRepository(
    private val feed: List<TonightFeedItemUi>,
    private val current: TonightSessionUi?,
) : TonightRepository {
    override suspend fun loadFeed(
        radiusMeters: Int,
        intent: String?,
    ): AppResult<List<TonightFeedItemUi>> = AppResult.Success(if (intent == null) feed else feed.filter { it.intentRaw == intent })

    override suspend fun loadCurrentSession(): AppResult<TonightSessionUi?> = AppResult.Success(current)

    override suspend fun endSession(sessionId: String): AppResult<TonightSessionUi> = AppResult.Success(current!!)
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
        hostMode = "hosting",
        note = "around tonight",
        photoUrl = null,
        distanceLabel = "1.2 km",
        distanceMeters = 1_200,
        zoneKey = "10_20",
        linkedPartner = null,
    )

class PlayingTonightScreenTest {
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun rowsRenderAndIntentPillFiltersList() =
        runComposeUiTest {
            val holder =
                PlayingTonightStateHolder(
                    StaticTonightRepository(feed = listOf(item("a", "solo"), item("b", "social")), current = null),
                )
            setContent {
                PlayingTonightScreen(stateHolder = holder, onBack = {}, onOpenProfile = {}, onMessage = {})
            }
            waitForIdle()

            onNodeWithTag(PlayingTonightTags.row("a")).assertIsDisplayed()
            onNodeWithTag(PlayingTonightTags.row("b")).assertIsDisplayed()

            // Filtering to Solo drops the social row.
            onNodeWithTag(PlayingTonightTags.intentPill("Solo")).performClick()
            waitForIdle()
            onNodeWithTag(PlayingTonightTags.row("a")).assertIsDisplayed()
            onNodeWithTag(PlayingTonightTags.row("b")).assertDoesNotExist()
        }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun liveSessionShowsEndAction() =
        runComposeUiTest {
            val session =
                TonightSessionUi(
                    id = "s1",
                    intent = TonightIntent.Solo,
                    intentRaw = "solo",
                    hostMode = "public_meet_first",
                    status = "active",
                    expiresAt = "2026-06-03T00:00:00Z",
                )
            val holder = PlayingTonightStateHolder(StaticTonightRepository(feed = emptyList(), current = session))
            setContent {
                PlayingTonightScreen(stateHolder = holder, onBack = {}, onOpenProfile = {}, onMessage = {})
            }
            waitForIdle()

            onNodeWithTag(PlayingTonightTags.LIVE_FLOATER).assertIsDisplayed()
            onNodeWithTag(PlayingTonightTags.END).assertIsDisplayed()
        }
}
