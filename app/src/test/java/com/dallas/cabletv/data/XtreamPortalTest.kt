package com.dallas.cabletv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class XtreamPortalTest {

    @Test
    fun playerAgentIsTheAllowlistedClient() {
        assertEquals("VLC/3.0.18 LibVLC/3.0.18", PLAYER_USER_AGENT)
    }

    @Test
    fun serverUrlDropsTheApiSuffixAndKeepsAPanelPath() {
        assertEquals(
            "https://example.test/panel",
            normalizeXtreamServerUrl("https://example.test/panel/player_api.php?username=a&password=b")
        )
        assertEquals(
            "http://example.com:8080",
            normalizeXtreamServerUrl("http://example.com:8080/get.php/")
        )
        assertEquals(
            "http://example.com:8080/portal",
            normalizeXtreamServerUrl("http://example.com:8080/portal/panel_api.php")
        )
        assertEquals(
            "https://example.com/tv",
            normalizeXtreamServerUrl("HTTPS://example.com/tv/GET.PHP")
        )
    }

    @Test
    fun pastedPlaylistFillsBlankCredentials() {
        val (server, user, pass) = xtreamLoginFields(
            "http://example.com:8080/get.php?username=ada&password=p%40ss",
            " ",
            ""
        )
        assertEquals("http://example.com:8080", server)
        assertEquals("ada", user)
        assertEquals("p@ss", pass)
    }

    @Test
    fun typedCredentialsWinOverTheUrl() {
        val (_, user, pass) = xtreamLoginFields(
            "http://example.com/player_api.php?username=fromurl&password=secret",
            "typed",
            "local"
        )
        assertEquals("typed", user)
        assertEquals("local", pass)
    }

    @Test
    fun blankServerStaysBlank() {
        assertEquals(Triple("", "ada", "secret"), xtreamLoginFields("  ", "ada", "secret"))
    }

    @Test
    fun urlUserInfoIsRejected() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            normalizeXtreamServerUrl("http://ada:secret@example.com")
        }
        assertTrue(error.message!!.contains("username"))
    }

    @Test
    fun unsupportedSchemeIsRejected() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            normalizeXtreamServerUrl("ftp://example.com/live")
        }
        assertTrue(error.message!!.contains("http"))
    }

    @Test
    fun accountStatusFollowsAuthTextAndExpiry() {
        val now = 1_700_000_000_000L
        assertEquals(PortalStatus.ACTIVE, resolvePortalStatus(1, null, null, now))
        assertEquals(PortalStatus.ACTIVE, resolvePortalStatus("1", "", null, now))
        assertEquals(PortalStatus.ACTIVE, resolvePortalStatus(true, null, "0", now))
        assertEquals(PortalStatus.ACTIVE, resolvePortalStatus(1, "Active", now / 1000 + 3600, now))
        assertEquals(PortalStatus.ACTIVE, resolvePortalStatus(1, "active", 0, now))
        assertEquals(PortalStatus.ACTIVE, resolvePortalStatus(1, "active", -5, now))
        assertEquals(PortalStatus.EXPIRED, resolvePortalStatus(1, "Active", now / 1000 - 10, now))
        assertEquals(PortalStatus.EXPIRED, resolvePortalStatus(1, "expired", null, now))
        assertEquals(PortalStatus.INACTIVE, resolvePortalStatus(0, "Active", null, now))
        assertEquals(PortalStatus.INACTIVE, resolvePortalStatus("0", null, null, now))
        assertEquals(PortalStatus.INACTIVE, resolvePortalStatus(false, null, null, now))
        assertEquals(PortalStatus.INACTIVE, resolvePortalStatus(1, "Banned", null, now))
        assertEquals(PortalStatus.UNAVAILABLE, resolvePortalStatus(null, "", null, now))
    }

    @Test
    fun futureAddedDatesDoNotRank() {
        val now = 1_700_000_000L
        val future = now + 48 * 3600
        assertEquals(0L, recentlyAddedEpochSeconds(future, future, series = false, nowEpochSeconds = now))
        val edge = now + 24 * 3600
        assertEquals(edge, recentlyAddedEpochSeconds(edge, null, series = false, nowEpochSeconds = now))
        assertEquals(0L, recentlyAddedEpochSeconds(edge + 1, null, series = false, nowEpochSeconds = now))
    }

    @Test
    fun moviesPreferAddedAndSeriesPreferModified() {
        val now = 1_800_000_000L
        assertEquals(100L, recentlyAddedEpochSeconds(100L, 200L, series = false, nowEpochSeconds = now))
        assertEquals(200L, recentlyAddedEpochSeconds(100L, 200L, series = true, nowEpochSeconds = now))
        assertEquals(150L, recentlyAddedEpochSeconds(now + 48 * 3600, 150L, series = false, nowEpochSeconds = now))
        assertEquals(1_700_000_000L, recentlyAddedEpochSeconds(1_700_000_000_000L, null, series = false, nowEpochSeconds = now))
    }

    @Test
    fun directSourceReplacesTheBuiltUrl() {
        assertEquals(
            "https://cdn.example/movie.mkv",
            contentStreamUrl("http://h", "u", "p", "movie", "3", "mp4", "https://cdn.example/movie.mkv")
        )
        assertEquals(
            "http://h/movie/u/p/3.mkv",
            contentStreamUrl("http://h", "u", "p", "movie", "3", "mkv", "ftp://nope")
        )
        assertEquals(
            "http://h/series/u/p/8.mp4",
            contentStreamUrl("http://h", "u", "p", "series", "8", " ", null)
        )
    }

    @Test
    fun livePrefersHlsAndKeepsATsFallback() {
        val both = liveStreamUrls("http://h", "u", "p", "4", listOf("m3u8", "ts"))
        assertEquals("http://h/live/u/p/4.m3u8", both.first)
        assertEquals("http://h/live/u/p/4.ts", both.second)

        val tsOnly = liveStreamUrls("http://h", "u", "p", "4", listOf("mpegts"))
        assertEquals("http://h/live/u/p/4.ts", tsOnly.first)
        assertNull(tsOnly.second)

        val hlsOnly = liveStreamUrls("http://h", "u", "p", "4", listOf("hls"))
        assertEquals("http://h/live/u/p/4.m3u8", hlsOnly.first)
        assertNull(hlsOnly.second)

        val unknown = liveStreamUrls("http://h", "u", "p", "4", emptyList())
        assertEquals("http://h/live/u/p/4.m3u8", unknown.first)
        assertEquals("http://h/live/u/p/4.ts", unknown.second)
    }

    @Test
    fun catchupStampUsesThePanelClock() {
        val summer = Instant.parse("2024-07-15T11:00:00Z").toEpochMilli()
        assertEquals("2024-07-15:12-00", formatCatchupStart(summer, "Europe/London"))
        assertEquals("2024-07-15:11-00", formatCatchupStart(summer, "UTC"))
        assertEquals("2024-07-15:14-00", formatCatchupStart(summer, "UTC+03:00"))
        val midnight = Instant.parse("2024-01-15T00:05:00Z").toEpochMilli()
        assertEquals("2024-01-15:00-05", formatCatchupStart(midnight, "UTC"))
    }

    @Test
    fun catchupUrlsPreferTsThenHls() {
        val start = Instant.parse("2024-07-15T11:00:00Z").toEpochMilli()
        val stop = start + 30 * 60_000L
        assertEquals(
            "http://h/timeshift/u/p%2Fa/30/2024-07-15:11-00/9.ts",
            catchupUrl("http://h", "u", "p/a", "9", start, stop, "UTC", CatchupVariant.REST_TS)
        )
        val legacy = catchupUrl("http://h", "u", "p", "9", start, stop, "Europe/London", CatchupVariant.LEGACY_M3U8)
        assertTrue(legacy.startsWith("http://h/streaming/timeshift.php?"))
        assertTrue(legacy.contains("start=2024-07-15:12-00"))
        assertTrue(legacy.contains("duration=30"))
        assertTrue(legacy.contains("extension=m3u8"))
        assertEquals(
            listOf(
                CatchupVariant.REST_TS,
                CatchupVariant.LEGACY_TS,
                CatchupVariant.REST_M3U8,
                CatchupVariant.LEGACY_M3U8
            ),
            catchupProbeOrder(listOf("m3u8", "ts"))
        )
        assertEquals(
            listOf(CatchupVariant.REST_M3U8, CatchupVariant.LEGACY_M3U8),
            catchupProbeOrder(listOf("hls"))
        )
        assertEquals(
            CatchupVariant.LEGACY_TS,
            chooseCatchupVariant(listOf("ts", "m3u8")) { variant ->
                if (variant == CatchupVariant.LEGACY_TS) 206 else 404
            }
        )
    }

    @Test
    fun unknownTimezoneUsesTheClockPair() {
        val epoch = Instant.parse("2024-06-01T12:00:00Z").epochSecond
        assertEquals(180, deriveOffsetMinutes("2024-06-01 15:00:20", epoch))
        assertEquals(-180, deriveOffsetMinutes("2024-06-01 09:00:00", epoch))
        val noon = Instant.parse("2024-06-01T12:00:00Z").toEpochMilli()
        val fromAlias = resolveServerTimezone("UTC+3", "2024-06-01 15:00:20", epoch)
        assertEquals("2024-06-01:15-00", formatCatchupStart(noon, fromAlias))
        assertEquals(
            "UTC+03:00",
            resolveServerTimezone("Not/AZone", "2024-06-01 15:00:20", epoch)
        )
        assertEquals("Europe/London", resolveServerTimezone("Europe/London", null, null))
        assertNull(resolveServerTimezone("Not/AZone", null, null))
        assertNull(parseNaiveUtcMs("2024-13-01 00:00:00"))
    }

    @Test
    fun replayListStaysInsideTheArchiveWindow() {
        val now = 10_000_000L
        val programs = listOf(
            Program("old", now - 5 * 3_600_000L, now - 4 * 3_600_000L),
            Program("recent", now - 2 * 3_600_000L, now - 3_600_000L),
            Program("tiny", now - 30_000L, now - 1_000L),
            Program("current", now - 60_000L, now + 60_000L)
        )
        assertEquals(listOf("recent"), finishedWithin(programs, now, 2 * 3_600_000L).map { it.title })
    }

    @Test
    fun archiveFlagAndDuration() {
        assertEquals(true to 4, catchupOffered("1", "4"))
        assertEquals(true to 2, catchupOffered(0, 2))
        assertEquals(false to 0, catchupOffered("0", null))
    }
}
