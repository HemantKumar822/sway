package com.sway.designui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

class AdaptiveTest {

    @Test fun widthClass_boundaries() {
        assertEquals(WindowWidthClass.Compact, Adaptive.widthClass(0))
        assertEquals(WindowWidthClass.Compact, Adaptive.widthClass(599))
        assertEquals(WindowWidthClass.Medium, Adaptive.widthClass(600))
        assertEquals(WindowWidthClass.Medium, Adaptive.widthClass(839))
        assertEquals(WindowWidthClass.Expanded, Adaptive.widthClass(840))
        assertEquals(WindowWidthClass.Expanded, Adaptive.widthClass(1280))
    }

    @Test fun contentMaxWidth_is640() {
        assertEquals(640, Adaptive.CONTENT_MAX_WIDTH.value.toInt())
    }
}
