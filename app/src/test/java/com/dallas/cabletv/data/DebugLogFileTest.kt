package com.dallas.cabletv.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DebugLogFileTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun keepsTheNewestWholeLinesOnceTheFileIsFull() {
        val file = folder.newFile("debug_log.txt")
        repeat(100) { appendCapped(file, "line $it\n", maxBytes = 300) }
        val text = file.readText()
        assertTrue(file.length() <= 300)
        assertTrue(text.endsWith("line 99\n"))
        assertFalse(text.startsWith("line 0\n"))
        assertTrue(text.lines().dropLast(1).all { it.startsWith("line ") })
    }

    @Test
    fun leavesASmallFileAsItWas() {
        val file = folder.newFile("debug_log.txt")
        appendCapped(file, "one\n", maxBytes = 300)
        appendCapped(file, "two\n", maxBytes = 300)
        assertEquals("one\ntwo\n", file.readText())
    }
}
