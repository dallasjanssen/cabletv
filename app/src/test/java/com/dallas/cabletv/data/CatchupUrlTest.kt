package com.dallas.cabletv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CatchupUrlTest {

    private val start = 1_700_000_000_000L
    private val end = start + 3_600_000L

    @Test
    fun appendAddsUtcQuery() {
        val url = m3uCatchupUrl("http://example/live.m3u8", "append", null, start, end, start)
        assertEquals("http://example/live.m3u8?utc=1700000000&lutc=1700003600", url)
    }

    @Test
    fun templateReplacesPlaceholders() {
        val url = m3uCatchupUrl(
            "http://example/live",
            "default",
            "http://example/catchup/\${start}/\${duration}",
            start,
            end,
            start
        )
        assertEquals("http://example/catchup/1700000000/3600", url)
    }

    @Test
    fun shiftUsesHowFarBackTheShowStarted() {
        val url = m3uCatchupUrl("http://example/live", "shift", null, start, end, start + 600_000L)
        assertEquals("http://example/live?shift=600", url)
    }

    @Test
    fun flussonicRewritesThePlaylist() {
        val url = m3uCatchupUrl("http://example/channel/index.m3u8", "flussonic", null, start, end, start)
        assertEquals("http://example/channel/archive-1700000000-3600.m3u8", url)
    }

    @Test
    fun emptyRangeIsNotAUrl() {
        assertNull(m3uCatchupUrl("http://example/live", "append", null, start, start, start))
    }
}

class ReminderTest {
    @Test
    fun dueInsideTheWindowAndNotAlreadyShown() {
        val reminder = Reminder("1", "BBC", "News", 10_000L)
        val due = dueReminders(listOf(reminder), nowMs = 10_500L, fired = emptySet())
        assertEquals(listOf(reminder), due)
        assertTrue(dueReminders(listOf(reminder), nowMs = 10_500L, fired = setOf(reminderKey(reminder))).isEmpty())
    }
}

class HlsAndPortalTest {
    @Test
    fun highestVariantIsChosen() {
        val master = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=1000
            low.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=5000
            high.m3u8
        """.trimIndent()
        org.junit.Assert.assertEquals("http://cdn.example/high.m3u8", hlsVariantUrl(master, "http://cdn.example/master.m3u8"))
        org.junit.Assert.assertEquals(null, hlsVariantUrl("#EXTM3U\n#EXTINF:4,\nseg.ts\n", "http://cdn.example/media.m3u8"))
    }

    @Test
    fun macAddressIsNormalized() {
        org.junit.Assert.assertEquals("00:1A:79:AB:CD:EF", normalizeStalkerMac("00-1a-79-ab-cd-ef"))
    }

    @Test
    fun scheduleIsDueOnlyInsideItsWindow() {
        val item = ScheduledRecording("1", "c", "News", "Show", "http://x", 1_000, 5_000)
        org.junit.Assert.assertEquals(1, dueSchedule(listOf(item), 2_000, emptySet()).size)
        org.junit.Assert.assertEquals(0, dueSchedule(listOf(item), 2_000, setOf("1")).size)
        org.junit.Assert.assertEquals(0, dueSchedule(listOf(item), 9_000, emptySet()).size)
    }
}

class LockTest {
    @Test
    fun lockMatchesAGroupOnlyWhenAPinExists() {
        assertTrue(isLocked("BBC", "Sports", listOf("SPORTS"), pinSet = true))
        assertTrue(!isLocked("BBC", "Sports", listOf("SPORTS"), pinSet = false))
        assertTrue(!isLocked("BBC", "News", listOf("SPORTS"), pinSet = true))
    }
}
