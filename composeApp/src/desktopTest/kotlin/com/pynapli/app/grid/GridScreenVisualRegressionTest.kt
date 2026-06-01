package com.pynapli.app.grid

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.pynapli.app.design.PynapliTheme
import com.pynapli.app.profile.ProfileDetailStateHolder
import com.pynapli.app.profile.SamplePublicProfileRepository
import kotlin.test.Test
import kotlin.test.assertTrue

class GridScreenVisualRegressionTest {
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun gridScreenKeepsPremiumDarkVisualSignature() =
        runComposeUiTest {
            val cards = visualRegressionCards()
            val gridStateHolder = GridStateHolder(StaticGridVisualRepository(cards))
            val profileDetailStateHolder = ProfileDetailStateHolder(SamplePublicProfileRepository())
            val gridState = LazyGridState()

            setContent {
                PynapliTheme(themeMode = "dark") {
                    Box(
                        modifier =
                            Modifier
                                .size(width = 390.dp, height = 844.dp)
                                .testTag(GRID_VISUAL_ROOT_TAG),
                    ) {
                        GridScreen(
                            stateHolder = gridStateHolder,
                            profileDetailStateHolder = profileDetailStateHolder,
                            profileImageUrl = null,
                            gridState = gridState,
                        )
                    }
                }
            }

            waitUntil(timeoutMillis = 5_000) { gridStateHolder.state.value.cards.size == cards.size }
            waitForIdle()

            onNodeWithTag(GRID_VISUAL_ROOT_TAG)
                .captureToImage()
                .assertGridVisualSignature(label = "initial grid")

            onNodeWithTag(GridFilterTags.OPEN_SHEET).performClick()
            waitForIdle()

            onNodeWithTag(GRID_VISUAL_ROOT_TAG)
                .captureToImage()
                .assertFilterSheetVisualSignature()
        }
}

private const val GRID_VISUAL_ROOT_TAG = "grid-visual-root"

private fun visualRegressionCards(): List<GridProfileCardUi> =
    (0 until 54).map { index ->
        GridProfileCardUi(
            id = "visual-card-$index",
            displayName =
                when (index % 6) {
                    0 -> "Maya"
                    1 -> "Alex"
                    2 -> "Noa"
                    3 -> "Sage"
                    4 -> "Rae"
                    else -> "Quinn"
                },
            online = index % 2 == 0,
            distanceLabel = "${index + 1} km",
            tone = GridCardTone.entries[index % GridCardTone.entries.size],
            linkedPartner =
                if (index % 5 == 0) {
                    GridLinkedPartnerUi(
                        userId = "partner-$index",
                        displayName = "Partner $index",
                    )
                } else {
                    null
                },
            linkedPartnerTone = LinkedPartnerTone.entries[index % LinkedPartnerTone.entries.size],
            distanceMeters = (index + 1) * 1_000,
        )
    }

private class StaticGridVisualRepository(
    private val cards: List<GridProfileCardUi>,
) : GridFeedRepository {
    override suspend fun loadGrid(
        filters: GridFilters,
        cursor: String?,
    ): GridFeedUi = GridFeedUi(cards = cards, adSupported = false)
}

private fun ImageBitmap.assertGridVisualSignature(label: String) {
    val signature = visualSignature()

    assertTrue(signature.darkRatio > 0.20f, "$label should keep a black-background base: $signature")
    // Combined dark + subdued-surface coverage. Threshold recalibrated 2026-05-31 when the
    // palette was refined to the design handoff (PynapliColors): the raised surface dropped from
    // #1A1A1A (lum 0.10, which fell in BOTH the dark <0.13 and sheetSurface 0.09-0.22 bands and
    // was double-counted) to #16161C (lum ~0.086, counted once as dark). The screen is just as
    // subdued; the sum shrank only because the band overlap shrank. 0.52 still asserts a
    // majority-dark premium surface.
    assertTrue(signature.darkRatio + signature.sheetSurfaceRatio > 0.52f, "$label should remain predominantly dark/subdued: $signature")
    assertTrue(signature.midToneRatio > 0.25f, "$label should contain visible Grid card tone blocks: $signature")
    assertTrue(signature.brightRatio > 0.006f, "$label should contain legible light text/icons: $signature")
    assertTrue(signature.onlineGreenPixels > 8, "$label should render online indicators: $signature")
    assertTrue(signature.goldPixels > 5, "$label should retain the Pynapli gold accent: $signature")
}

private fun ImageBitmap.assertFilterSheetVisualSignature() {
    val signature = visualSignature()

    assertTrue(signature.darkRatio > 0.50f, "filter sheet should preserve dark premium surface: $signature")
    assertTrue(signature.sheetSurfaceRatio > 0.12f, "filter sheet should render a visible subdued surface: $signature")
    assertTrue(signature.goldPixels > 50, "filter sheet should keep primary action/accent visible: $signature")
    assertTrue(signature.brightRatio > 0.012f, "filter sheet should contain readable controls: $signature")
}

private fun ImageBitmap.visualSignature(sampleStep: Int = 4): VisualSignature {
    val pixels = toPixelMap()
    var sampled = 0
    var dark = 0
    var midTone = 0
    var bright = 0
    var onlineGreen = 0
    var gold = 0
    var sheetSurface = 0

    var y = 0
    while (y < pixels.height) {
        var x = 0
        while (x < pixels.width) {
            val color = pixels[x, y]
            val red = color.red
            val green = color.green
            val blue = color.blue
            val luminance = color.luminanceApprox()

            sampled += 1
            if (luminance < 0.13f) dark += 1
            if (luminance in 0.16f..0.56f && maxOf(red, green, blue) - minOf(red, green, blue) > 0.08f) {
                midTone += 1
            }
            if (luminance > 0.74f) bright += 1
            if (green > 0.62f && red < 0.24f && blue in 0.22f..0.62f) onlineGreen += 1
            if (red > 0.82f && green > 0.62f && blue < 0.24f) gold += 1
            if (luminance in 0.09f..0.22f && maxOf(red, green, blue) - minOf(red, green, blue) < 0.08f) {
                sheetSurface += 1
            }

            x += sampleStep
        }
        y += sampleStep
    }

    return VisualSignature(
        sampledPixels = sampled,
        darkRatio = dark.toFloat() / sampled,
        midToneRatio = midTone.toFloat() / sampled,
        brightRatio = bright.toFloat() / sampled,
        onlineGreenPixels = onlineGreen,
        goldPixels = gold,
        sheetSurfaceRatio = sheetSurface.toFloat() / sampled,
    )
}

private fun Color.luminanceApprox(): Float = (0.2126f * red) + (0.7152f * green) + (0.0722f * blue)

private data class VisualSignature(
    val sampledPixels: Int,
    val darkRatio: Float,
    val midToneRatio: Float,
    val brightRatio: Float,
    val onlineGreenPixels: Int,
    val goldPixels: Int,
    val sheetSurfaceRatio: Float,
)
