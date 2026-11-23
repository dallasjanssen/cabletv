package com.dallas.cabletv.data

import org.junit.Assert.assertEquals
import org.junit.Test

class HlsPlaylistTest {
    private val base = "http://cdn.example/live/ch1/index.m3u8?token=abc"

    @Test
    fun absoluteUrlIsKept() {
        assertEquals("https://other.example/a.ts", resolveHlsUrl(base, "https://other.example/a.ts"))
    }

    @Test
    fun relativePathUsesTheBaseDirectoryAndDropsItsQuery() {
        assertEquals("http://cdn.example/live/ch1/seg1.ts", resolveHlsUrl(base, "seg1.ts"))
        assertEquals("http://cdn.example/live/ch1/hi/seg1.ts", resolveHlsUrl(base, "hi/seg1.ts"))
    }

    @Test
    fun rootRelativePathUsesTheHostRoot() {
        assertEquals("http://cdn.example/segments/a.ts", resolveHlsUrl(base, "/segments/a.ts"))
    }

    @Test
    fun schemeRelativeKeepsTheBaseScheme() {
        assertEquals("http://edge.example/a.ts", resolveHlsUrl(base, "//edge.example/a.ts"))
        assertEquals("https://edge.example/a.ts", resolveHlsUrl("https://cdn.example/x/index.m3u8", "//edge.example/a.ts"))
    }

    @Test
    fun segmentsAreResolvedInOrder() {
        val text = "#EXTM3U\n#EXTINF:4,\nseg1.ts\n#EXTINF:4,\n/root/seg2.ts\n"
        assertEquals(
            listOf("http://cdn.example/live/ch1/seg1.ts", "http://cdn.example/root/seg2.ts"),
            hlsSegments(text, base)
        )
    }
}
