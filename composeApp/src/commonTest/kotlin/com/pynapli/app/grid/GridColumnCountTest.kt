package com.pynapli.app.grid

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Locks the one layout rule the Grid and its loading skeleton must agree on.
 *
 * They disagreed for real: the skeleton hardcoded 3 columns, which was correct while the grid was
 * phone-only and wrong from the moment W2 made it adaptive — leaving every wide-pane first load
 * (most visibly on web, which has no cached first paint) showing three full-height bars instead of
 * a preview of the grid. Both now call [gridColumnCountFor]; these cases stop it drifting again.
 */
class GridColumnCountTest {
    @Test
    fun `phone width panes keep the locked 3-up grid`() {
        assertEquals(3, gridColumnCountFor(390.dp))
        assertEquals(3, gridColumnCountFor(430.dp))
        // Just under the wide-pane threshold is still the phone grid.
        assertEquals(3, gridColumnCountFor(599.dp))
    }

    @Test
    fun `wide panes get more columns rather than bigger tiles`() {
        // The founder-locked rule: more width means MORE people on screen, never a blown-up
        // phone grid. So every wide pane must beat the phone's 3.
        val wide = gridColumnCountFor(1440.dp)
        assertTrue(wide > 3, "1440dp pane should exceed the phone's 3 columns, was $wide")

        // ...and it must be monotonic — a wider pane never shows fewer columns.
        var previous = gridColumnCountFor(GridWidePaneMinWidth)
        listOf(700, 900, 1100, 1440, 1920, 2560).forEach { width ->
            val columns = gridColumnCountFor(width.dp)
            assertTrue(
                columns >= previous,
                "columns went backwards at ${width}dp: $previous -> $columns",
            )
            previous = columns
        }
    }

    @Test
    fun `tiles never fall below the minimum side on a wide pane`() {
        // Matching GridCells.Adaptive means the resulting tile is at least GridWideTileMinSize;
        // if this fails the skeleton would predict more columns than the real grid renders.
        listOf(600, 700, 901, 1440, 1920).forEach { width ->
            val pane = width.dp
            val columns = gridColumnCountFor(pane)
            val tileSide = (pane - GridTileGap * (columns - 1)) / columns
            assertTrue(
                tileSide >= GridWideTileMinSize,
                "at ${width}dp, $columns columns gives a ${tileSide.value}dp tile, " +
                    "below the ${GridWideTileMinSize.value}dp minimum",
            )
        }
    }

    @Test
    fun `degenerate widths still yield a usable column count`() {
        assertTrue(gridColumnCountFor(0.dp) >= 1)
        assertTrue(gridColumnCountFor(1.dp) >= 1)
    }

    @Test
    fun `large text means fewer, larger wide-pane tiles but leaves the phone grid alone`() {
        // 1356dp is the Grid pane beside the rail in a 1440px browser window.
        assertEquals(8, gridColumnCountFor(1356.dp, fontScale = 1f))
        assertEquals(5, gridColumnCountFor(1356.dp, fontScale = 1.5f))
        assertEquals(5, gridColumnCountFor(1356.dp, fontScale = 3f), "capped at the 1.5x the web applies")
        assertEquals(3, gridColumnCountFor(390.dp, fontScale = 1.5f), "phone panes stay 3-up")
    }
}
