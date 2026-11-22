package com.dallas.cabletv.data

import org.junit.Assert.assertEquals
import org.junit.Test

class M3uParserTest {

    @Test
    fun parsesChannelsWithAttributes() {
        val text = """
            #EXTM3U
            #EXTINF:-1 tvg-id="food.us" tvg-chno="515" tvg-logo="http://x/food.png" group-title="Cooking",Food HD
            http://host/live/food.ts
            #EXTINF:-1 group-title="News",Local News
            http://host/live/news.ts
        """.trimIndent()

        val channels = M3uParser.parse(text)

        assertEquals(2, channels.size)
        val food = channels.first { it.name == "Food HD" }
        assertEquals(515, food.number)
        assertEquals("Cooking", food.group)
        assertEquals("food.us", food.epgId)
        assertEquals("http://x/food.png", food.logo)
        assertEquals("http://host/live/food.ts", food.streamUrl)
    }

    @Test
    fun missingNumbersFallBackToPosition() {
        val text = "#EXTINF:-1,One\nhttp://a\n#EXTINF:-1,Two\nhttp://b\n"
        val channels = M3uParser.parse(text)
        assertEquals(listOf(1, 2), channels.map { it.number })
        assertEquals("Other", channels.first().group)
    }

    @Test
    fun duplicateGuideIdsDoNotMakeDuplicateChannelIds() {
        // HD and SD copies often share a tvg-id, but each has its own stream URL.
        val text = """
            #EXTINF:-1 tvg-id="espn.us",ESPN HD
            http://host/hd.ts
            #EXTINF:-1 tvg-id="espn.us",ESPN SD
            http://host/sd.ts
        """.trimIndent()
        val channels = M3uParser.parse(text)
        assertEquals(2, channels.map { it.id }.toSet().size)
        assertEquals(listOf("espn.us", "espn.us"), channels.map { it.epgId })
    }

    @Test
    fun repeatedUrlsStillGetUniqueIds() {
        val text = "#EXTINF:-1,A\nhttp://same\n#EXTINF:-1,B\nhttp://same\n"
        val ids = M3uParser.parse(text).map { it.id }
        assertEquals(2, ids.toSet().size)
    }

    @Test
    fun commasInAttributesDoNotBreakTheTitle() {
        val text = "#EXTINF:-1 group-title=\"News, Sports\",Sports Desk\nhttp://a\n"
        val channel = M3uParser.parse(text).single()
        assertEquals("Sports Desk", channel.name)
        assertEquals("News, Sports", channel.group)
    }

    @Test
    fun sortsByChannelNumber() {
        val text = "#EXTINF:-1 tvg-chno=\"9\",Nine\nhttp://a\n#EXTINF:-1 tvg-chno=\"2\",Two\nhttp://b\n"
        assertEquals(listOf("Two", "Nine"), M3uParser.parse(text).map { it.name })
    }
}
