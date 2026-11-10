package com.pynapli.app.design

import kotlin.test.Test
import kotlin.test.assertEquals

class PynapliTextScaleTest {
    @Test
    fun followsChromesFontSizeSteps() {
        assertEquals(1f, pynapliTextScaleForBrowserFont(16.0), "Medium (default)")
        assertEquals(1.25f, pynapliTextScaleForBrowserFont(20.0), "Large")
        assertEquals(1.5f, pynapliTextScaleForBrowserFont(24.0), "Very large")
    }

    @Test
    fun neverShrinksAndCapsAtOneAndAHalf() {
        assertEquals(1f, pynapliTextScaleForBrowserFont(12.0), "Small does not shrink text")
        assertEquals(1f, pynapliTextScaleForBrowserFont(0.0), "a broken probe falls back to standard")
        assertEquals(1.5f, pynapliTextScaleForBrowserFont(40.0), "custom huge sizes are capped")
    }
}
