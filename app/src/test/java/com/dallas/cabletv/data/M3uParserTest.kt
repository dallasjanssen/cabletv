package com.dallas.cabletv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class M3uParserTest {

    private val sample = """
        #EXTM3U url-tvg="https://example.com/epg.xml,https://other.example/epg.xml"
        #EXTINF:-1 tvg-id="bbc1" tvg-chno="7" group-title="News",UK| BBC One
        http://example.com/1.ts
        #EXTINF:-1 tvg-id="bbc1" group-title="News",UK| BBC One HD
        http://example.com/2.ts
    """.trimIndent()

    @Test
    fun guideUrlComesFromTheHeaderLine() {
        assertEquals(
            "https://example.com/epg.xml",
            M3uParser.guideUrlFrom(sample.lines().first())
        )
    }

    @Test
    fun repeatedTvgIdsStillGiveUniqueChannelIds() {
        val channels = M3uParser.parse(sample)
        assertEquals(2, channels.size)
        assertNotEquals(channels[0].id, channels[1].id)
        assertEquals("bbc1", channels[0].epgId)
    }

    @Test
    fun catchupTagsAreKept() {
        val text = """
            #EXTM3U
            #EXTINF:-1 tvg-id="one" catchup="append" catchup-days="5" catchup-source="http://example/archive/${'$'}{start}",News
            http://example.com/live.m3u8
            #EXTINF:-1 timeshift="2",Plain
            http://example.com/plain.ts
        """.trimIndent()
        val channels = M3uParser.parse(text)
        assertEquals(true, channels[0].catchup)
        assertEquals(5, channels[0].archiveDays)
        assertEquals("append", channels[0].catchupType)
        assertEquals("playlist", channels[0].origin)
        assertEquals(true, channels[1].catchup)
        assertEquals(2, channels[1].archiveDays)
    }

    @Test
    fun numbersComeFromTvgChnoOrPosition() {
        val channels = M3uParser.parse(sample)
        assertEquals(listOf(2, 7), channels.map { it.number })
    }

    @Test
    fun titleIsAfterTheCommaOutsideQuotes() {
        val text = "#EXTM3U\n#EXTINF:-1 group-title=\"News, Sports\" tvg-id=\"cnn\",CNN\nhttp://example.com/cnn.ts\n"
        val ch = M3uParser.parse(text).single()
        assertEquals("CNN", ch.name)
        assertEquals("News, Sports", ch.group)
        assertEquals("cnn", ch.epgId)
    }

    @Test
    fun singleQuotedAttributesAndBomAreAccepted() {
        val text = "\uFEFF#EXTM3U\n#EXTINF:-1 tvg-id='a b' group-title='Kids',Cartoons\nhttp://example.com/k.ts\n"
        val ch = M3uParser.parse(text).single()
        assertEquals("Cartoons", ch.name)
        assertEquals("Kids", ch.group)
        assertEquals("a b", ch.epgId)
    }

    @Test
    fun idsDoNotShiftWhenAChannelIsAddedBefore() {
        val a = "#EXTINF:-1,A\nhttp://example.com/a.ts\n#EXTINF:-1,B\nhttp://example.com/b.ts\n"
        val before = M3uParser.parse(a).first { it.name == "B" }.id
        val after = M3uParser.parse("#EXTINF:-1,Z\nhttp://example.com/z.ts\n$a").first { it.name == "B" }.id
        assertEquals(before, after)
        assertTrue(before.startsWith("m3u:"))
        assertTrue(!before.contains("example.com"))
    }

    @Test
    fun repeatedUrlsGetASuffix() {
        val text = "#EXTINF:-1,A\nhttp://example.com/a.ts\n#EXTINF:-1,A2\nhttp://example.com/a.ts\n"
        val ids = M3uParser.parse(text).map { it.id }
        assertEquals(2, ids.toSet().size)
        assertTrue(ids.any { it.endsWith("#2") })
    }
}
