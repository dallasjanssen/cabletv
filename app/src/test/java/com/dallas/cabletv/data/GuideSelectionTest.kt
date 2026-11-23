package com.dallas.cabletv.data

import org.junit.Assert.assertEquals
import org.junit.Test

class GuideSelectionTest {

    private val start = 1_000L
    private val end = 5_000L

    @Test
    fun currentShowWithCatchupPlaysFromTheStart() {
        assertEquals(GuideSelection.Replay, guideSelection(start, end, 3_000L, placeholder = false, catchup = true))
    }

    @Test
    fun finishedShowWithCatchupPlaysFromTheStart() {
        assertEquals(GuideSelection.Replay, guideSelection(start, end, 6_000L, placeholder = false, catchup = true))
    }

    @Test
    fun currentShowWithoutCatchupTunesLive() {
        assertEquals(GuideSelection.WatchLive, guideSelection(start, end, 3_000L, placeholder = false, catchup = false))
    }

    @Test
    fun finishedShowWithoutCatchupIsNotLive() {
        assertEquals(GuideSelection.Unavailable, guideSelection(start, end, 6_000L, placeholder = false, catchup = false))
    }

    @Test
    fun laterShowWaits() {
        assertEquals(GuideSelection.Later, guideSelection(start, end, 500L, placeholder = false, catchup = true))
    }

    @Test
    fun placeholderTunesLive() {
        assertEquals(GuideSelection.WatchLive, guideSelection(0L, Long.MAX_VALUE, 3_000L, placeholder = true, catchup = false))
    }
}

class ProgrammeSearchTest {

    private val schedule = mapOf(
        "bbc" to listOf(
            Program("News", 1_000L, 2_000L),
            Program("Match of the Day", 3_000L, 5_000L)
        ),
        "itv" to listOf(Program("The Chase", 2_500L, 4_000L))
    )

    @Test
    fun findsShowsStillToComeAndSkipsOnesThatEnded() {
        val hits = matchProgrammes(schedule, "match", nowMs = 2_500L)
        assertEquals(listOf("bbc"), hits.map { it.first })
        assertEquals("Match of the Day", hits.single().second.title)
    }

    @Test
    fun includesWhatIsOnNow() {
        val hits = matchProgrammes(schedule, "chase", nowMs = 3_000L)
        assertEquals("The Chase", hits.single().second.title)
    }

    @Test
    fun needsTwoCharacters() {
        assertEquals(emptyList<Pair<String, Program>>(), matchProgrammes(schedule, "m", nowMs = 0L))
    }
}
