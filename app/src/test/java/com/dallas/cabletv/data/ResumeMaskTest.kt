package com.dallas.cabletv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ResumeMaskTest {
    private val creds = StreamCredentials("user name", "p@ss/word", "tok123")

    @Test
    fun xtreamPathIsMasked() {
        val masked = maskStreamUrl("http://host:8080/movie/bob/secret/123.mp4")
        assertEquals("http://host:8080/movie/@@XU@@/@@XP@@/123.mp4", masked)
        assertFalse(masked.contains("secret"))
    }

    @Test
    fun allXtreamKindsAreMasked() {
        for (kind in listOf("movie", "series", "live", "timeshift")) {
            assertFalse(maskStreamUrl("http://h/$kind/u1/p1/5.ts").contains("u1"))
        }
    }

    @Test
    fun queryValuesAreMasked() {
        val masked = maskStreamUrl("http://h/x?username=bob&password=pw&api_key=abc&maxHeight=480")
        assertEquals("http://h/x?username=@@XU@@&password=@@XP@@&api_key=@@JT@@&maxHeight=480", masked)
    }

    @Test
    fun jellyfinTokenIsMasked() {
        val masked = maskStreamUrl("http://jf/Videos/9/stream?static=true&api_key=tok123")
        assertEquals("http://jf/Videos/9/stream?static=true&api_key=@@JT@@", masked)
    }

    @Test
    fun fillEncodesLikeTheBuilders() {
        val url = "http://host/movie/@@XU@@/@@XP@@/1.mp4"
        assertEquals("http://host/movie/user%20name/p%40ss%2Fword/1.mp4", fillStreamUrl(url, creds))
        assertEquals("http://jf/s?api_key=tok123", fillStreamUrl("http://jf/s?api_key=@@JT@@", creds))
    }

    @Test
    fun withoutCredentialsPlaceholdersStay() {
        val url = "http://host/movie/@@XU@@/@@XP@@/1.mp4"
        assertEquals(url, fillStreamUrl(url, null))
    }

    @Test
    fun maskingIsIdempotentAndRoundTrips() {
        val original = "http://host/series/user%20name/p%40ss%2Fword/7.mkv"
        val once = maskStreamUrl(original)
        assertEquals(once, maskStreamUrl(once))
        assertEquals(original, fillStreamUrl(once, creds))
    }

    @Test
    fun plainUrlsAreUntouched() {
        val url = "http://cdn.example.com/img/poster.jpg"
        assertEquals(url, maskStreamUrl(url))
    }
}
