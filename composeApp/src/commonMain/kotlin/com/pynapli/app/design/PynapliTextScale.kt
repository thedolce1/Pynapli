package com.pynapli.app.design

/**
 * Text scale for a browser whose default font size is [defaultFontPx] (CSS px; 16 is the standard).
 *
 * Compose draws its own text, so the web app ignored the browser's font-size setting entirely: a
 * member who set Chrome to "Large" saw no change. Many members use the web on a computer, and older
 * users are the ones who change that setting. This maps it onto Compose's fontScale the way Android
 * maps its system font size, so only text grows; layout units stay put (window size classes and the
 * Grid's column count do not change).
 *
 * Never shrinks below the standard size (a "Small" browser setting is usually an accident and small
 * text is the failure we are fixing), and caps at 1.5x ("Very large"), the largest size the layouts
 * are checked at.
 */
fun pynapliTextScaleForBrowserFont(defaultFontPx: Double): Float = (defaultFontPx / STANDARD_BROWSER_FONT_PX).toFloat().coerceIn(1f, MAX_TEXT_SCALE)

private const val STANDARD_BROWSER_FONT_PX = 16.0
private const val MAX_TEXT_SCALE = 1.5f
