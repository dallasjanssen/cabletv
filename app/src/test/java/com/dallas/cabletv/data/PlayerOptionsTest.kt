package com.dallas.cabletv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerOptionsTest {

    @Test
    fun autoBufferKeepsTheMemoryRewind() {
        val plan = bufferPlan(BUFFER_AUTO, compact = false, autoRewindMs = 90_000)
        assertEquals(90_000, plan.maxMs)
        assertEquals(90_000, plan.backMs)
    }

    @Test
    fun compactPicturesStaySmallRegardlessOfPreset() {
        val plan = bufferPlan(BUFFER_VERY_LARGE, compact = true, autoRewindMs = 180_000)
        assertEquals(15_000, plan.maxMs)
        assertEquals(0, plan.backMs)
    }

    @Test
    fun udpProxyRewritesMulticastAddressesOnly() {
        assertEquals(
            "http://192.168.1.5:4022/udp/239.1.1.1:5000",
            proxiedStreamUrl("udp://239.1.1.1:5000", "192.168.1.5:4022")
        )
        assertEquals("http://example/live.m3u8", proxiedStreamUrl("http://example/live.m3u8", "192.168.1.5:4022"))
        assertEquals("udp://239.1.1.1:5000", proxiedStreamUrl("udp://239.1.1.1:5000", " "))
    }

    @Test
    fun tidyNameStripsCountryThenExtraAffixes() {
        assertEquals("BBC One", tidyChannelName("UK| BBC One | HD", stripCountry = true, prefixes = "", suffixes = "| HD"))
        assertEquals("News", tidyChannelName("US: News", stripCountry = false, prefixes = "US:", suffixes = ""))
    }

    @Test
    fun orderKeepsUnknownChannelsAfterTheSavedOnes() {
        fun ch(id: String) = LiveChannel(id, 1, id, null, "G", "u", null)
        val ordered = orderChannels(listOf(ch("c"), ch("a"), ch("b")), "b,a")
        assertEquals(listOf("b", "a", "c"), ordered.map { it.id })
    }

    @Test
    fun recordingPaddingAndPinGrace() {
        val (start, end) = recordingWindow(1_000_000, 2_000_000, 2, 5)
        assertEquals(1_000_000 - 120_000, start)
        assertEquals(2_000_000 + 300_000, end)
        assertTrue(pinGraceCovers(PIN_GRACE_10M, 1_000, 1_000 + 9 * 60_000, "a", "b"))
        assertFalse(pinGraceCovers(PIN_GRACE_10M, 1_000, 1_000 + 11 * 60_000, "a", "b"))
        assertTrue(pinGraceCovers(PIN_GRACE_UNTIL_CLEAR, 1_000, 9_000_000, "a", "a"))
        assertFalse(pinGraceCovers(PIN_GRACE_UNTIL_CLEAR, 1_000, 9_000_000, "a", "b"))
        assertFalse(pinGraceCovers(PIN_GRACE_NONE, 1_000, 2_000, "a", "a"))
    }

    @Test
    fun assignmentsAndGroupsParse() {
        val options = PlayerOptions(
            epgAssignments = "ch1=guide.bbc, ch2=guide.cnn",
            customGroups = "Kids=a,b\nNews=c"
        )
        assertEquals("guide.bbc", options.epgIdFor("ch1", "fallback"))
        assertEquals("fallback", options.epgIdFor("missing", "fallback"))
        assertEquals(listOf("a", "b"), options.customGroupMap()["Kids"])
    }

    @Test
    fun engineSignatureChangesWhenThePlayerMustBeRebuilt() {
        val base = PlayerOptions()
        assertEquals(base.engineSignature(), base.copy(zapDelayMs = 900).engineSignature())
        org.junit.Assert.assertNotEquals(base.engineSignature(), base.copy(bufferPreset = BUFFER_LARGE).engineSignature())
        org.junit.Assert.assertNotEquals(base.engineSignature(), base.copy(userAgent = "Cable").engineSignature())
    }

    @Test
    fun oneChannelCanBeHiddenMovedGroupedAndMatched() {
        fun ch(id: String) = LiveChannel(id, id.removePrefix("c").toInt(), id, null, "G", "u", null)
        val channels = listOf(ch("c1"), ch("c2"), ch("c3"))
        assertEquals("c2,c1,c3", moveInOrder(channels, "", "c2", -1))
        assertEquals("c1", toggleCsvId("", "c1"))
        assertEquals("", toggleCsvId("c1", "c1"))
        assertEquals("c1=guide.one", assignGuide("", "c1", "guide.one"))
        assertEquals("", assignGuide("c1=guide.one", "c1", " "))
        assertEquals("Kids=c1", editCustomGroup("", "Kids", "c1", true))
        assertEquals("", editCustomGroup("Kids=c1", "Kids", "c1", false))
        assertEquals(listOf("c1"), channelMatches(channels, "1").map { it.id })
        assertEquals(emptyList<String>(), channelMatches(channels, "x").map { it.id })
    }
}
