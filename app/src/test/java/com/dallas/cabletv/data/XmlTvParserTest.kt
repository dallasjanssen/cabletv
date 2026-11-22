package com.dallas.cabletv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class XmlTvParserTest {

    private val xml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <tv>
          <channel id="food.us"><display-name>Food HD</display-name></channel>
          <programme start="20260828140000 +0000" stop="20260828150000 +0000" channel="food.us">
            <title lang="en">Tyler's Ultimate</title>
            <desc lang="en">Spaghetti and meatballs &amp; more.</desc>
            <category lang="en">Cooking</category>
            <rating system="MPAA"><value>NR</value></rating>
          </programme>
          <programme start="20260828150000 +0000" stop="20260828153000 +0000" channel="food.us">
            <title>Cooking for Real</title>
          </programme>
          <programme start="20200101000000 +0000" stop="20200101010000 +0000" channel="food.us">
            <title>Old Show</title>
          </programme>
          <programme start="20260828140000 +0000" stop="20260828150000 +0000" channel="other">
            <title>Elsewhere</title>
          </programme>
        </tv>
    """.trimIndent()

    private val keepAfter = utc(2026, 8, 28, 0, 0)

    @Test
    fun groupsProgramsByChannelAndSortsThem() {
        val guide = XmlTvParser.parse(xml, keepAfter)
        assertEquals(setOf("food.us", "other"), guide.keys)
        val food = guide.getValue("food.us")
        assertEquals(listOf("Tyler's Ultimate", "Cooking for Real"), food.map { it.title })
        assertTrue(food[0].start < food[1].start)
    }

    @Test
    fun readsDescriptionRatingAndCategory() {
        val first = XmlTvParser.parse(xml, keepAfter).getValue("food.us").first()
        assertEquals("Spaghetti and meatballs & more.", first.description)
        assertEquals("NR", first.rating)
        assertEquals("Cooking", first.category)
        assertEquals(utc(2026, 8, 28, 14, 0), first.start)
        assertEquals(utc(2026, 8, 28, 15, 0), first.end)
    }

    @Test
    fun dropsProgramsThatEndedLongAgo() {
        val titles = XmlTvParser.parse(xml, keepAfter).getValue("food.us").map { it.title }
        assertTrue(!titles.contains("Old Show"))
    }

    @Test
    fun honoursOffsetsInTimes() {
        val withOffset = XmlTvParser.parseTime("20260828140000 +0200")
        assertEquals(utc(2026, 8, 28, 12, 0), withOffset)
    }

    @Test
    fun missingOffsetMeansUtc() {
        assertEquals(utc(2026, 8, 28, 14, 0), XmlTvParser.parseTime("20260828140000"))
    }

    @Test
    fun badTimesAreRejected() {
        assertNull(XmlTvParser.parseTime("nope"))
    }

    private fun utc(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long {
        val cal = java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.clear()
        cal.set(y, mo - 1, d, h, mi, 0)
        return cal.timeInMillis
    }
}
