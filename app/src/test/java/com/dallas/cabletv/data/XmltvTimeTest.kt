package com.dallas.cabletv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.zip.GZIPOutputStream

class XmltvTimeTest {
    private val elevenUtc = LocalDateTime.of(2026, 11, 23, 11, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    @Test
    fun readsTheTimesProvidersSend() {
        assertEquals(elevenUtc, xmltvTimeMs("20261123110000 +0000"))
        assertEquals(elevenUtc, xmltvTimeMs("20261123110000+0000"))
        assertEquals(elevenUtc, xmltvTimeMs("20261123110000 +00:00"))
        assertEquals(elevenUtc, xmltvTimeMs("20261123110000"))
        assertEquals(elevenUtc, xmltvTimeMs("2026-11-23T11:00:00Z"))
        assertEquals(elevenUtc, xmltvTimeMs("2026-11-23 11:00:00 +0000"))
        assertEquals(elevenUtc + 6 * 3_600_000L, xmltvTimeMs("20261123110000 -0600"))
        assertEquals(0L, xmltvTimeMs("not a time"))
        assertEquals(0L, xmltvTimeMs("20261123 110000 +0000"))
    }

    @Test
    fun readsTimesWithoutSeconds() {
        assertEquals(elevenUtc, xmltvTimeMs("202611231100 +0000"))
        assertEquals(elevenUtc, xmltvTimeMs("202611231100"))
        assertEquals(elevenUtc - 5 * 3_600_000L - 45 * 60_000L, xmltvTimeMs("202611231100 +0545"))
        assertEquals(0L, xmltvTimeMs("2026112311"))
    }

    @Test
    fun keysIgnoreCaseAndPunctuation() {
        assertEquals("tntus", normalizeEpgKey("TNT.us"))
        assertEquals("bbcone1", normalizeEpgKey(" BBC One (1) "))
        assertEquals("", normalizeEpgKey("-. "))
    }

    @Test
    fun oneKeyCannotMeanTwoChannels() {
        val aliases = buildEpgAliases(
            setOf("TNT.us"),
            listOf("TNT" to "TNT.us", "TNT" to "TNT.east")
        )
        assertEquals("TNT.us", aliases["tntus"])
        assertEquals(null, aliases["tnt"])
    }

    @Test
    fun nameFillsAHoleAtTheCurrentHour() {
        val now = 1_000_000L
        val stale = listOf(Program("Old", now - 10_000L, now - 1_000L))
        val live = listOf(Program("Live", now - 1_000L, now + 1_000L))
        assertEquals("Live", chooseListings(stale, live, now).single().title)
        assertEquals("Old", chooseListings(stale, emptyList(), now).single().title)
        assertEquals("Live", chooseListings(live, stale, now).single().title)
    }

    @Test
    fun gzipBodyIsInflated() {
        val raw = "<?xml version=\"1.0\"?><tv></tv>".toByteArray()
        val gz = ByteArrayOutputStream()
        GZIPOutputStream(gz).use { it.write(raw) }
        val text = ungzipIfNeeded(ByteArrayInputStream(gz.toByteArray())).readBytes().decodeToString()
        assertTrue(text.contains("<tv>"))
        assertEquals("plain", ungzipIfNeeded(ByteArrayInputStream("plain".toByteArray())).readBytes().decodeToString())
    }
}
