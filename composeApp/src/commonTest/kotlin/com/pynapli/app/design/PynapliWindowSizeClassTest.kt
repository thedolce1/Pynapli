package com.pynapli.app.design

import kotlin.test.Test
import kotlin.test.assertEquals

class PynapliWindowSizeClassTest {
    @Test
    fun phoneWidthsAreCompact() {
        assertEquals(PynapliWindowSizeClass.Compact, pynapliWindowSizeClassForWidth(0f))
        assertEquals(PynapliWindowSizeClass.Compact, pynapliWindowSizeClassForWidth(390f))
        assertEquals(PynapliWindowSizeClass.Compact, pynapliWindowSizeClassForWidth(599.9f))
    }

    @Test
    fun tabletPortraitWidthsAreMedium() {
        assertEquals(PynapliWindowSizeClass.Medium, pynapliWindowSizeClassForWidth(600f))
        assertEquals(PynapliWindowSizeClass.Medium, pynapliWindowSizeClassForWidth(720f))
        assertEquals(PynapliWindowSizeClass.Medium, pynapliWindowSizeClassForWidth(839.9f))
    }

    @Test
    fun desktopWidthsAreExpanded() {
        assertEquals(PynapliWindowSizeClass.Expanded, pynapliWindowSizeClassForWidth(840f))
        assertEquals(PynapliWindowSizeClass.Expanded, pynapliWindowSizeClassForWidth(1512f))
    }
}
