package com.dallas.cabletv.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class NowLineHeightTest {
    @Test
    fun shortListKeepsOneRowPerChannel() {
        assertEquals(500f, nowLineMaxDp(rows = 10, density = 2.5f), 0.01f)
    }

    @Test
    fun longListStaysInsideWhatComposeCanLayOut() {
        val dp = nowLineMaxDp(rows = 20_000, density = 3f)
        assertEquals(262_142f, dp * 3f, 0.01f)
    }

    @Test
    fun emptyListHasNoLine() {
        assertEquals(0f, nowLineMaxDp(rows = 0, density = 2.75f), 0.01f)
    }
}
