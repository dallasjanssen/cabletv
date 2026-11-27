package com.dallas.cabletv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpgMergeTest {
    private val hour = 3_600_000L

    @Test
    fun aCopyWithADescriptionReplacesOneWithout() {
        val bare = Program("News", 0L, hour)
        val described = Program("News", 0L, hour, "The headlines")
        assertTrue(betterProgram(bare, described))
        assertFalse(betterProgram(described, bare))
    }

    @Test
    fun aCopyWithAStopTimeReplacesOneWithoutWhenDescriptionsMatch() {
        val open = Program("Open", 0L, 0L)
        val timed = Program("Open", 0L, hour)
        assertTrue(betterProgram(open, timed))
        assertFalse(betterProgram(timed, open))
    }

    @Test
    fun firstCopyStaysWhenNeitherIsBetter() {
        assertFalse(betterProgram(Program("First", 0L, hour), Program("Second", 0L, hour)))
    }

    @Test
    fun longDescriptionsAreCut() {
        val clipped = clipDesc("word ".repeat(200))
        assertTrue(clipped.length <= 401)
        assertTrue(clipped.endsWith("…"))
        assertEquals("Short", clipDesc("  Short  "))
    }
}
