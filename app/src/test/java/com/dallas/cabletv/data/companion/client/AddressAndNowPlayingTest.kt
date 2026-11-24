package com.dallas.cabletv.data.companion.client

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AddressAndNowPlayingTest {
    private fun ok(host: String, port: String) = (TvAddress.parse(host, port) as TvAddress.Result.Ok).address
    private fun problem(host: String, port: String) = (TvAddress.parse(host, port) as TvAddress.Result.Problem).message

    @Test
    fun portDefaultsTo8766() {
        assertEquals(TvAddress("192.168.1.20", 8766), ok("192.168.1.20", ""))
        assertEquals(TvAddress("192.168.1.20", 9000), ok(" 192.168.1.20 ", " 9000 "))
    }

    @Test
    fun portMayBeWrittenAfterTheHost() {
        assertEquals(TvAddress("tv.local", 8770), ok("tv.local:8770", ""))
        assertEquals(TvAddress("192.168.1.20", 8766), ok("192.168.1.20:", ""))
    }

    @Test
    fun ipv6AddressIsNotSplitAtItsColons() {
        assertEquals(TvAddress("fe80::1", 8766), ok("fe80::1", ""))
    }

    @Test
    fun badInputGetsAShortSentence() {
        assertTrue(problem("", "").contains("IP address"))
        assertTrue(problem("  ", "9").contains("IP address"))
        assertTrue(problem("192.168 1.2", "").contains("spaces"))
        assertTrue(problem("192.168.1.20", "abc").contains("port"))
        assertTrue(problem("192.168.1.20", "0").contains("port"))
        assertTrue(problem("192.168.1.20", "70000").contains("port"))
    }

    private fun state(json: String) = TvState.fromJson(JSONObject(json))

    @Test
    fun notConnectedAndNothingOnAreSaidPlainly() {
        assertEquals(NowPlaying("Not connected", null, false, null), nowPlaying(null))
        assertEquals(NowPlaying("Nothing is on", "Guide", false, null), nowPlaying(state("""{"screen":"guide","state":"idle"}""")))
        assertEquals(NowPlaying("Nothing is on", null, false, null), nowPlaying(state("""{"screen":"?","state":"idle"}""")))
    }

    @Test
    fun playingShowsTitleChannelLogoAndPausedFlag() {
        val live = nowPlaying(
            state("""{"screen":"live","state":"playing","title":"SportsCenter","channel":"ESPN","channel_number":206,"image":"http://i"}""")
        )
        assertEquals("SportsCenter", live.title)
        assertEquals("ESPN, channel 206", live.detail)
        assertEquals("http://i", live.image)
        assertFalse(live.paused)

        val paused = nowPlaying(state("""{"screen":"live","state":"paused","title":"News","channel":"CNN"}"""))
        assertTrue(paused.paused)
        assertEquals("CNN", paused.detail)

        assertEquals("Channel 7", nowPlaying(state("""{"state":"playing","title":"X","channel_number":7}""")).detail)
        assertNull(nowPlaying(state("""{"state":"playing","title":"A movie","kind":"movie"}""")).detail)
    }

    @Test
    fun keyAvailabilityFollowsTheRoute() {
        assertTrue(keyAvailable(RemoteKey.POWER, atvConnected = true, companionOnline = false))
        assertFalse(keyAvailable(RemoteKey.POWER, atvConnected = false, companionOnline = true))
        assertTrue(keyAvailable(RemoteKey.OK, atvConnected = false, companionOnline = true))
        assertFalse(keyAvailable(RemoteKey.OK, atvConnected = false, companionOnline = false))
        assertTrue(keyAvailable(RemoteKey.OK, atvConnected = true, companionOnline = false))
    }
}
