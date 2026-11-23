package com.dallas.cabletv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EpgMergeTest {
    private val hour = 3_600_000L

    @Test
    fun twoFeedsWithTheSameListingsKeepEachShowOnce() {
        val xtream = mapOf("bbc" to listOf(Program("News", 0L, hour), Program("Film", hour, 3 * hour)))
        val guideUrl = mapOf(
            "bbc" to listOf(Program("News", 0L, hour, "The headlines"), Program("Film", hour, 3 * hour)),
            "itv" to listOf(Program("Quiz", 0L, hour))
        )
        val merged = mergeSchedules(listOf(xtream, guideUrl))
        assertEquals(listOf("News", "Film"), merged.getValue("bbc").map { it.title })
        assertEquals("The headlines", merged.getValue("bbc").first().desc)
        assertEquals(1, merged.getValue("itv").size)
    }

    @Test
    fun mergedListIsSortedByStart() {
        val a = mapOf("bbc" to listOf(Program("Late", 2 * hour, 3 * hour)))
        val b = mapOf("bbc" to listOf(Program("Early", 0L, hour)))
        assertEquals(listOf("Early", "Late"), mergeSchedules(listOf(a, b)).getValue("bbc").map { it.title })
    }

    @Test
    fun firstEntryWinsWhenNeitherHasADescription() {
        val list = dedupeByStart(listOf(Program("First", 0L, hour), Program("Second", 0L, hour)))
        assertEquals("First", list.single().title)
    }

    @Test
    fun missingStopUsesTheNextStartOrHalfAnHour() {
        val filled = fillMissingStops(listOf(Program("A", 0L, 0L), Program("B", hour, 0L)))
        assertEquals(hour, filled[0].end)
        assertEquals(hour + 30 * 60_000L, filled[1].end)
    }

    @Test
    fun tidyKeepsTheWindowAndFillsStops() {
        val list = listOf(
            Program("Old", 0L, hour),
            Program("Open", 5 * hour, 0L),
            Program("Next", 6 * hour, 7 * hour),
            Program("Open", 5 * hour, 0L, "Duplicate with a description")
        )
        val tidy = tidySchedule(list, from = 2 * hour, to = 10 * hour)
        assertEquals(listOf("Open", "Next"), tidy.map { it.title })
        assertEquals(6 * hour, tidy[0].end)
        assertEquals("Duplicate with a description", tidy[0].desc)
    }

    @Test
    fun longDescriptionsAreCut() {
        val clipped = clipDesc("word ".repeat(200))
        assertTrue(clipped.length <= 401)
        assertTrue(clipped.endsWith("…"))
        assertEquals("Short", clipDesc("  Short  "))
    }
}
