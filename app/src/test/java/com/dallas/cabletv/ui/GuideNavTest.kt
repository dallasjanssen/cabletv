package com.dallas.cabletv.ui

import com.dallas.cabletv.data.Program
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GuideNavTest {
    private val min = 60_000L
    private val slot = 30 * min
    private val hour = 60 * min

    @Test
    fun overlappingShowsSitSideBySide() {
        val cells = guideCells(
            listOf(
                Program("A", 0L, hour + 5 * min),
                Program("B", hour, 2 * hour),
                Program("Inside B", hour + 10 * min, hour + 20 * min)
            )
        )
        assertEquals(listOf("A", "B", "Inside B"), cells.map { it.program.title })
        assertEquals(hour, cells[0].end)
        assertEquals(hour + 10 * min, cells[1].end)
        assertEquals(hour + 20 * min, cells[2].end)
    }

    @Test
    fun rightReachesAShowThatStartsBeforeThePreviousEnds() {
        val cells = guideCells(listOf(Program("A", 0L, hour + 5 * min), Program("B", hour, 2 * hour)))
        val step = stepRight(cells, 0, windowStart = 0L, span = 2 * hour, lastWindow = 10 * hour)
        assertEquals(hour, step?.start)
        assertEquals(0L, step?.windowStart)
    }

    @Test
    fun rightFromALongShowJumpsToTheNextOne() {
        val cells = guideCells(listOf(Program("Film", 0L, 5 * hour), Program("News", 5 * hour, 6 * hour)))
        val step = stepRight(cells, 0, windowStart = 0L, span = 2 * hour, lastWindow = 10 * hour)
        assertEquals(5 * hour, step?.start)
        // Moved on just far enough that the news starts in the last half hour of the window.
        assertEquals(3 * hour + slot, step?.windowStart)
    }

    @Test
    fun rightStopsAtTheEndOfTheGuide() {
        val cells = guideCells(listOf(Program("A", 0L, hour), Program("B", 20 * hour, 21 * hour)))
        assertNull(stepRight(cells, 0, windowStart = 0L, span = 2 * hour, lastWindow = 10 * hour))
        assertNull(stepRight(cells, 1, windowStart = 0L, span = 2 * hour, lastWindow = 30 * hour))
    }

    @Test
    fun leftFromAShowThatStartedBeforeTheWindowGoesToThePreviousShow() {
        val cells = guideCells(listOf(Program("Early", 0L, hour), Program("Film", hour, 5 * hour)))
        val step = stepLeft(cells, 1, windowStart = 4 * hour, earliest = -10 * hour)
        assertEquals(0L, step.start)
        assertEquals(0L, step.windowStart)
    }

    @Test
    fun leftWithNothingEarlierGoesToTheChannelName() {
        val cells = guideCells(listOf(Program("Old", -20 * hour, -12 * hour), Program("Film", -12 * hour, 5 * hour)))
        val step = stepLeft(cells, 1, windowStart = 4 * hour, earliest = -10 * hour)
        assertNull(step.start)
        assertEquals(4 * hour, step.windowStart)
        assertNull(stepLeft(cells, 0, windowStart = 4 * hour, earliest = -30 * hour).start)
    }

    @Test
    fun cellAtPrefersTheShowOnThenTheNextOne() {
        val cells = guideCells(listOf(Program("A", 0L, hour), Program("B", 2 * hour, 3 * hour)))
        assertEquals(0, cellIndexAt(cells, 30 * min, 0L, 2 * hour))
        assertEquals(1, cellIndexAt(cells, hour + 10 * min, 0L, 2 * hour + slot))
        // A gap at that time with nothing later in the window falls back to the show before it.
        assertEquals(0, cellIndexAt(cells, hour + 10 * min, 0L, 2 * hour))
        assertEquals(-1, cellIndexAt(cells, 4 * hour, 3 * hour + slot, 5 * hour))
    }

    @Test
    fun slotsFollowTheLocalClock() {
        val offset = 5 * hour + 45 * min
        // 10:00 UTC is 15:45 in a +05:45 zone, so the window starts at 15:30 local, 09:45 UTC.
        assertEquals(9 * hour + 45 * min, localSlotStart(10 * hour, offset))
        assertEquals(10 * hour, localSlotStart(10 * hour + 10 * min, 0L))
        assertEquals(-slot, localSlotStart(-1L, 0L))
    }

    @Test
    fun numberPadKeysTypeDigits() {
        assertEquals(0, digitOf(7))
        assertEquals(9, digitOf(16))
        assertEquals(5, digitOf(149))
        assertNull(digitOf(19))
    }
}
