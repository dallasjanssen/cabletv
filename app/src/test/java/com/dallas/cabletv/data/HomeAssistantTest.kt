package com.dallas.cabletv.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeAssistantTest {

    private val espn = LiveChannel(
        id = "1234", number = 206, name = "ESPN", logo = "http://logos/espn.png",
        group = "Sports", streamUrl = "u", epgId = null
    )

    // 2026-11-24 18:00 and 19:00 UTC.
    private val show = Program(title = "SportsCenter", start = 1_795_543_200_000L, end = 1_795_546_800_000L)

    @Test
    fun liveChannelWithAShow() {
        val info = liveNowPlaying(espn, show)
        assertEquals("live", info.kind)
        assertEquals("SportsCenter", info.title)
        assertEquals("ESPN · Channel 206", info.subtitle)

        val json = JSONObject(nowPlayingJson("Living Room TV", HA_PLAYING, info))
        assertEquals("Living Room TV", json.getString("device"))
        assertEquals("playing", json.getString("state"))
        assertEquals("live", json.getString("kind"))
        assertEquals("SportsCenter", json.getString("title"))
        assertEquals("ESPN", json.getString("channel"))
        assertEquals(206, json.getInt("channel_number"))
        assertEquals("1234", json.getString("channel_id"))
        assertEquals("2026-11-24T18:00:00Z", json.getString("start"))
        assertEquals("2026-11-24T19:00:00Z", json.getString("end"))
        assertEquals("http://logos/espn.png", json.getString("image"))
    }

    @Test
    fun liveChannelWithoutAGuideUsesTheChannelName() {
        val info = liveNowPlaying(espn, null)
        assertEquals("ESPN", info.title)
        val json = JSONObject(nowPlayingJson("TV", HA_PAUSED, info))
        assertEquals("paused", json.getString("state"))
        assertFalse(json.has("start"))
        assertFalse(json.has("end"))
    }

    @Test
    fun replayNamesTheReplayedShow() {
        val earlier = Program(title = "NFL Live", start = 1L, end = 2L)
        val info = liveNowPlaying(espn, show, replay = earlier)
        assertEquals("replay", info.kind)
        assertEquals("NFL Live", info.title)
        assertEquals("ESPN · Replay", info.subtitle)
    }

    @Test
    fun episodeAndMovie() {
        val episode = vodNowPlaying(
            VodItem(
                id = "e1", name = "Pilot", year = 2008, overview = null, posterUrl = "http://p", streamUrl = "s",
                type = "Episode", season = 1, episode = 1, seriesName = "Breaking Bad"
            )
        )
        assertEquals("episode", episode.kind)
        assertEquals("Breaking Bad · S1 E1", episode.subtitle)
        val json = JSONObject(nowPlayingJson("TV", HA_PLAYING, episode))
        assertEquals("Breaking Bad", json.getString("series"))
        assertEquals(1, json.getInt("season"))
        assertEquals(1, json.getInt("episode"))
        assertFalse(json.has("channel"))

        val movie = vodNowPlaying(
            VodItem(id = "m1", name = "Heat", year = 1995, overview = null, posterUrl = "", streamUrl = "s", type = "Movie")
        )
        assertEquals("movie", movie.kind)
        assertEquals("1995", movie.subtitle)
        assertEquals(null, movie.image)
        assertFalse(JSONObject(nowPlayingJson("TV", HA_PLAYING, movie)).has("series"))
    }

    @Test
    fun idleSaysOnlyWhichTv() {
        val json = JSONObject(nowPlayingJson("Bedroom TV", HA_IDLE, liveNowPlaying(espn, show)))
        assertEquals(setOf("event", "device", "state"), json.keys().asSequence().toSet())
        assertEquals("now_playing", json.getString("event"))
        assertEquals("idle", json.getString("state"))
    }

    @Test
    fun favoritesAreTheVisibleChannelsSortedByNumber() {
        fun ch(id: String, number: Int, name: String) =
            LiveChannel(id = id, number = number, name = name, logo = null, group = "", streamUrl = "u", epgId = null)
        val channels = listOf(
            ch("a", 206, "ESPN"),
            ch("b", 0, "Local News"),
            ch("c", 12, "NBC"),
            ch("d", 300, "espn"),
            ch("e", 50, "HBO")
        )
        val list = favoriteChannels(channels, setOf("a", "b", "c", "d", "zzz"))
        assertEquals(
            listOf(FavoriteChannel("NBC", 12), FavoriteChannel("ESPN", 206), FavoriteChannel("Local News", 0)),
            list
        )
        assertEquals(emptyList<FavoriteChannel>(), favoriteChannels(channels, emptySet()))
    }

    @Test
    fun favoritesMessage() {
        val json = JSONObject(favoritesJson("Bedroom TV", listOf(FavoriteChannel("NBC", 12), FavoriteChannel("Local News", 0))))
        assertEquals("favorites", json.getString("event"))
        assertEquals("Bedroom TV", json.getString("device"))
        val items = json.getJSONArray("favorites")
        assertEquals(2, items.length())
        assertEquals("NBC", items.getJSONObject(0).getString("name"))
        assertEquals(12, items.getJSONObject(0).getInt("number"))
        assertFalse(items.getJSONObject(1).has("number"))
        assertEquals(0, JSONObject(favoritesJson("TV", emptyList())).getJSONArray("favorites").length())
    }

    @Test
    fun webhookAddresses() {
        assertEquals("http://homeassistant.local:8123/api/webhook/x", normalizeWebhookUrl(" homeassistant.local:8123/api/webhook/x "))
        assertEquals("https://ha.example.com/api/webhook/x", normalizeWebhookUrl("https://ha.example.com/api/webhook/x"))
        assertEquals("", normalizeWebhookUrl("  "))
        assertTrue(isWebhookUrl(""))
        assertTrue(isWebhookUrl("HTTP://ha/api/webhook/x"))
        assertFalse(isWebhookUrl("ftp://ha/api/webhook/x"))
    }
}
