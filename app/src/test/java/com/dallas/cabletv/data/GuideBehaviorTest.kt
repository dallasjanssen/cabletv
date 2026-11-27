package com.dallas.cabletv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class GuideBehaviorTest {

    @Test
    fun zeroOffsetLeavesTheSameList() {
        val list = listOf(Program("News", 1_000L, 2_000L, "Tonight"))
        assertSame(list, applyGuideOffset(list, 0))
        assertSame(emptyList<Program>(), applyGuideOffset(emptyList(), 30))
    }

    @Test
    fun offsetMovesStartAndEndByTheSameAmount() {
        val shifted = applyGuideOffset(listOf(Program("News", 0L, 60 * 60_000L, "Tonight")), 30)
        assertEquals(30 * 60_000L, shifted[0].start)
        assertEquals(90 * 60_000L, shifted[0].end)
        assertEquals("Tonight", shifted[0].desc)
        assertEquals(0L, storedGuideInstant(shifted[0].start, 30))
    }

    @Test
    fun handRefreshIsNeverSkipped() {
        val saved = 1_000L
        assertFalse(shouldSkipGuideRefresh(true, false, 6, saved, saved + 60_000L, true))
        assertFalse(shouldSkipGuideRefresh(true, true, 0, saved, saved, false))
    }

    @Test
    fun startRefreshCanBeSkippedWithoutAddingATimer() {
        assertTrue(shouldSkipGuideRefresh(false, false, 0, null, 5_000L, true))
        assertFalse(shouldSkipGuideRefresh(false, true, 0, 0L, 3_600_000L, true))
        // After the start download, a zero hour count leaves the existing hourly loop alone.
        assertFalse(shouldSkipGuideRefresh(false, false, 0, 0L, 60_000L, false))
    }

    @Test
    fun hourCountSkipsWhileTheLastSaveIsNewer() {
        val saved = 10_000L
        val hour = 3_600_000L
        assertTrue(shouldSkipGuideRefresh(false, true, 6, saved, saved + hour, false))
        assertFalse(shouldSkipGuideRefresh(false, true, 6, saved, saved + 6 * hour, false))
        assertFalse(shouldSkipGuideRefresh(false, true, 6, null, saved, false))
        assertFalse(shouldSkipGuideRefresh(false, true, 6, saved + hour, saved, false))
    }

    @Test
    fun logoFileMatchesNameWithoutPunctuationOrCase() {
        val dir = Files.createTempDirectory("logos").toFile()
        try {
            java.io.File(dir, "BBC One.png").writeBytes(byteArrayOf(1))
            java.io.File(dir, "Channel_4.webp").writeBytes(byteArrayOf(1))
            java.io.File(dir, "notes.txt").writeBytes(byteArrayOf(1))
            assertEquals("BBC One.png", LogoFiles.find(dir.absolutePath, "bbc-one!")?.name)
            assertEquals("Channel_4.webp", LogoFiles.find(dir.absolutePath, "Channel 4")?.name)
            assertNull(LogoFiles.find(dir.absolutePath, "CNN"))
            assertNull(LogoFiles.find(dir.absolutePath, "notes"))
            assertNull(LogoFiles.find("/no/such/logo-folder", "BBC One"))
            assertNull(LogoFiles.find("  ", "BBC One"))
        } finally {
            dir.deleteRecursively()
        }
    }
}
