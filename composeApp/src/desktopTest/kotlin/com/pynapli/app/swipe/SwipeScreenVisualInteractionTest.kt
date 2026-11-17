package com.pynapli.app.swipe

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.pynapli.app.conversation.ConversationStateHolder
import com.pynapli.app.conversation.SampleConversationRepository
import com.pynapli.app.design.PynapliTheme
import com.pynapli.app.profile.ProfileDetailStateHolder
import com.pynapli.app.profile.SamplePublicProfileRepository
import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.dto.LinkedPartnerMiniDto
import com.pynapli.shared.network.dto.MatchDto
import com.pynapli.shared.network.dto.SwipeAction
import com.pynapli.shared.network.dto.SwipeCardDto
import com.pynapli.shared.network.dto.SwipeDeckRequestDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SwipeScreenVisualInteractionTest {
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun swipeScreenKeepsPremiumCardVisualSignature() =
        runComposeUiTest {
            val cards = visualSwipeCards()
            val stateHolder = SwipeStateHolder(StaticSwipeVisualRepository(cards))

            setSwipeContent(stateHolder)

            waitUntil(timeoutMillis = 5_000) { stateHolder.state.value.cards.size == cards.size }
            waitForIdle()

            onNodeWithTag(SwipeTestTags.TOP_CARD).assertExists()
            onNodeWithTag(SwipeTestTags.ROOT)
                .captureToImage()
                .assertSwipeVisualSignature()
        }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun swipeActionsAdvanceDeckAndPineappleLikeCarriesItsNote() =
        runComposeUiTest {
            mainClock.autoAdvance = true
            val cards = visualSwipeCards()
            val repository = StaticSwipeVisualRepository(cards)
            var actionIndex = 0
            val stateHolder =
                SwipeStateHolder(
                    repository = repository,
                    actionIdFactory = { "00000000-0000-0000-0000-${(++actionIndex).toString().padStart(12, '0')}" },
                )

            setSwipeContent(stateHolder)
            waitUntil(timeoutMillis = 5_000) {
                stateHolder.state.value.topCard
                    ?.userId == cards.first().userId
            }
            waitForIdle()

            onNodeWithTag(SwipeTestTags.LIKE_ACTION).performClick()
            waitForIdle()
            mainClock.advanceTimeBy(SWIPE_FLY_OUT_TEST_MS)
            waitForIdle()
            waitUntil(timeoutMillis = 5_000) {
                repository.submittedActions.any { it.action == SwipeAction.LIKE } &&
                    stateHolder.state.value.topCard
                        ?.userId == cards[1].userId
            }

            onNodeWithTag(SwipeTestTags.PASS_ACTION).performClick()
            waitForIdle()
            mainClock.advanceTimeBy(SWIPE_FLY_OUT_TEST_MS)
            waitForIdle()
            waitUntil(timeoutMillis = 5_000) {
                repository.submittedActions.any { it.action == SwipeAction.PASS } &&
                    stateHolder.state.value.topCard
                        ?.userId == cards[2].userId
            }

            // The Pineapple Like button opens its sheet; the note rides on the same swipe-up.
            onNodeWithTag(SwipeTestTags.PINEAPPLE_LIKE_ACTION).performClick()
            waitForIdle()
            onNodeWithTag(SwipeTestTags.PINEAPPLE_LIKE_SHEET).assertExists()
            onNodeWithTag(SwipeTestTags.PINEAPPLE_LIKE_NOTE_FIELD).performTextInput("Coffee this week?")
            onNodeWithTag(SwipeTestTags.PINEAPPLE_LIKE_SEND).performClick()
            waitForIdle()
            mainClock.advanceTimeBy(SWIPE_FLY_OUT_TEST_MS)
            waitForIdle()
            waitUntil(timeoutMillis = 5_000) { repository.submittedActions.size == 3 }

            assertEquals(
                listOf(SwipeAction.LIKE to null, SwipeAction.PASS to null, SwipeAction.PINEAPPLE_LIKE to "Coffee this week?"),
                repository.submittedActions.map { it.action to it.noteText },
            )
        }
}

@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.setSwipeContent(stateHolder: SwipeStateHolder) {
    setContent {
        PynapliTheme(themeMode = "dark") {
            Box(modifier = Modifier.size(width = 390.dp, height = 844.dp)) {
                SwipeScreen(
                    stateHolder = stateHolder,
                    profileDetailStateHolder = ProfileDetailStateHolder(SamplePublicProfileRepository()),
                    conversationStateHolder = ConversationStateHolder(SampleConversationRepository()),
                    viewerUserId = "viewer",
                )
            }
        }
    }
}

private fun visualSwipeCards(): List<SwipeCardDto> =
    (0 until 8).map { index ->
        SwipeCardDto(
            userId = "visual-card-$index",
            displayName =
                when (index % 5) {
                    0 -> "Maya"
                    1 -> "Alex"
                    2 -> "Noa"
                    3 -> "Sage"
                    else -> "Quinn"
                },
            displayAge = 31 + index,
            distanceLabel = "${index + 1} km",
            distanceMeters = (index + 1) * 1_000,
            linkedPartner =
                if (index % 3 == 0) {
                    LinkedPartnerMiniDto(
                        userId = "partner-$index",
                        displayName = "Partner $index",
                    )
                } else {
                    null
                },
            rankToken = "rank-token-$index",
        )
    }

private class StaticSwipeVisualRepository(
    private val cards: List<SwipeCardDto>,
) : SwipeRepository {
    val submittedActions = mutableListOf<QueuedSwipeAction>()

    override suspend fun loadDeck(request: SwipeDeckRequestDto): AppResult<SwipeDeckUi> =
        AppResult.Success(
            SwipeDeckUi(
                deckSessionId = "visual-session",
                cards = cards,
                nextCursor = null,
            ),
        )

    override suspend fun loadTopPicks(): AppResult<SwipeDeckUi> =
        AppResult.Success(
            SwipeDeckUi(
                deckSessionId = "visual-picks",
                cards = cards,
                nextCursor = null,
            ),
        )

    override suspend fun submitAction(action: QueuedSwipeAction): AppResult<MatchDto?> {
        submittedActions += action
        return AppResult.Success(null)
    }

    override suspend fun rewind(): AppResult<SwipeRewindUi?> = AppResult.Success(null)
}

private fun ImageBitmap.assertSwipeVisualSignature() {
    val signature = visualSignature()

    assertTrue(signature.darkRatio > 0.28f, "Swipe should keep a dark premium base: $signature")
    assertTrue(signature.darkRatio + signature.surfaceRatio > 0.42f, "Swipe should remain subdued, not bright/busy: $signature")
    assertTrue(signature.midToneRatio > 0.18f, "Swipe card should render a visible tonal card body: $signature")
    // Bright-text threshold lowered 0.006 -> 0.003 on 2026-06-01 (slice 1.3): the Swipe filter
    // row moved off solid-gold AssistChips (bright onSurface labels) onto restrained PynapliPills,
    // whose inactive labels use TextSecondary (#A8A29A, lum ~0.64 — below the 0.74 "bright" cut)
    // per the handoff pill language. The card name/age stay bright; only the filter chrome dimmed.
    assertTrue(signature.brightRatio > 0.003f, "Swipe should contain legible light text/icons: $signature")
    assertTrue(signature.goldPixels > 8, "Swipe should retain the Pynapli gold accent: $signature")
    assertTrue(signature.greenPixels > 3, "Swipe should render the like action accent: $signature")
}

private fun ImageBitmap.visualSignature(sampleStep: Int = 4): SwipeVisualSignature {
    val pixels = toPixelMap()
    var sampled = 0
    var dark = 0
    var surface = 0
    var midTone = 0
    var bright = 0
    var gold = 0
    var green = 0

    var y = 0
    while (y < pixels.height) {
        var x = 0
        while (x < pixels.width) {
            val color = pixels[x, y]
            val red = color.red
            val blue = color.blue
            val colorSpread = maxOf(red, color.green, blue) - minOf(red, color.green, blue)
            val luminance = color.luminanceApprox()

            sampled += 1
            if (luminance < 0.13f) dark += 1
            // Subdued surface band floor lowered 0.09 -> 0.08 on 2026-05-31 with the handoff
            // palette refine (raised surfaceVariant #1A1A1A ~0.102 -> #16161C ~0.088); see the
            // matching note in ConversationScreenInteractionTest. Base bg/surface stay below 0.08.
            if (luminance in 0.08f..0.24f && colorSpread < 0.08f) surface += 1
            if (luminance in 0.16f..0.60f && colorSpread > 0.07f) midTone += 1
            if (luminance > 0.74f) bright += 1
            if (red > 0.82f && color.green > 0.62f && blue < 0.24f) gold += 1
            if (color.green > 0.55f && red < 0.24f && blue in 0.20f..0.58f) green += 1

            x += sampleStep
        }
        y += sampleStep
    }

    return SwipeVisualSignature(
        sampledPixels = sampled,
        darkRatio = dark.toFloat() / sampled,
        surfaceRatio = surface.toFloat() / sampled,
        midToneRatio = midTone.toFloat() / sampled,
        brightRatio = bright.toFloat() / sampled,
        goldPixels = gold,
        greenPixels = green,
    )
}

private fun Color.luminanceApprox(): Float = (0.2126f * red) + (0.7152f * green) + (0.0722f * blue)

private const val SWIPE_FLY_OUT_TEST_MS = 350L

private data class SwipeVisualSignature(
    val sampledPixels: Int,
    val darkRatio: Float,
    val surfaceRatio: Float,
    val midToneRatio: Float,
    val brightRatio: Float,
    val goldPixels: Int,
    val greenPixels: Int,
)
