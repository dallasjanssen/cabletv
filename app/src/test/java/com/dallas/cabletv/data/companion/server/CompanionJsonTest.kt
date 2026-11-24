package com.dallas.cabletv.data.companion.server

import com.dallas.cabletv.data.HA_IDLE
import com.dallas.cabletv.data.HA_PLAYING
import com.dallas.cabletv.data.LiveChannel
import com.dallas.cabletv.data.NowPlayingInfo
import com.dallas.cabletv.data.Profile
import com.dallas.cabletv.data.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionJsonTest {

    private fun channel(id: String, number: Int, name: String, logo: String? = null) =
        LiveChannel(id = id, number = number, name = name, logo = logo, group = "Sports", streamUrl = "u", epgId = null)

    @Test
    fun stateHasTheScreenAndWhatIsPlayingWithoutEventAndDevice() {
        val info = NowPlayingInfo(
            kind = "live", title = "SportsCenter", channel = "ESPN", channelNumber = 206, channelId = "1234",
            start = 1_795_543_200_000L, end = 1_795_546_800_000L, image = "http://logos/espn.png"
        )
        val json = CompanionJson.stateMessage("live", HA_PLAYING, info)
        assertEquals("state", json.getString("type"))
        assertEquals("live", json.getString("screen"))
        assertEquals("playing", json.getString("state"))
        assertEquals("live", json.getString("kind"))
        assertEquals("SportsCenter", json.getString("title"))
        assertEquals("ESPN", json.getString("channel"))
        assertEquals(206, json.getInt("channel_number"))
        assertEquals("1234", json.getString("channel_id"))
        assertEquals("2026-11-24T18:00:00Z", json.getString("start"))
        assertEquals("2026-11-24T19:00:00Z", json.getString("end"))
        assertEquals("http://logos/espn.png", json.getString("image"))
        assertFalse(json.has("event"))
        assertFalse(json.has("device"))
        assertFalse(json.has("re"))
    }

    @Test
    fun idleStateSaysOnlyTheScreen() {
        val json = CompanionJson.stateMessage("settings", HA_IDLE, null)
        assertEquals(setOf("type", "screen", "state"), json.keys().asSequence().toSet())
        assertEquals("idle", json.getString("state"))
    }

    @Test
    fun favoritesAreOnlyChannelsInTheListSortedByNumber() {
        val channels = listOf(
            channel("3", 0, "Zed"),
            channel("2", 300, "BBC", logo = "http://logos/bbc.png"),
            channel("1", 206, "ESPN", logo = ""),
            channel("4", 5, "Alpha"),
            channel("9", 7, "Hidden but not listed")
        ).filterNot { it.id == "9" }
        val entries = CompanionJson.favoriteEntries(channels, setOf("1", "2", "3", "4", "9", "gone"))
        assertEquals(listOf("4", "1", "2", "3"), entries.map { it.id })
        assertEquals(listOf(5, 206, 300, 0), entries.map { it.number })

        val json = CompanionJson.favoritesJson(entries)
        assertEquals("Alpha", json.getJSONObject(0).getString("name"))
        // Logos that are missing or blank are left out.
        assertFalse(json.getJSONObject(0).has("logo"))
        assertFalse(json.getJSONObject(1).has("logo"))
        assertEquals("http://logos/bbc.png", json.getJSONObject(2).getString("logo"))
        // A channel without a number says 0.
        assertEquals(0, json.getJSONObject(3).getInt("number"))
    }

    @Test
    fun twoProfilesFavoritingTheSameChannelListItOnce() {
        val channels = listOf(channel("1", 206, "ESPN"), channel("1", 206, "ESPN"))
        assertEquals(1, CompanionJson.favoriteEntries(channels, setOf("1")).size)
    }

    @Test
    fun sameNumberSortsByName() {
        val channels = listOf(channel("2", 10, "beta"), channel("1", 10, "Alpha"))
        assertEquals(listOf("1", "2"), CompanionJson.favoriteEntries(channels, setOf("1", "2")).map { it.id })
    }

    @Test
    fun settingsHaveEverySectionTheSpecNames() {
        val settings = Settings(
            xtreamServer = "http://panel", xtreamUser = "u", xtreamPass = "p", m3uUrl = "http://list", epgUrl = "http://guide",
            stalkerUrl = "http://portal", stalkerMac = "00:1A:79:12:34:56", subscriptionExpiryMs = 99L,
            jellyfinServer = "http://jf", lockedFilters = "ADULT", stripPrefixes = true, homeAssistantWebhook = "http://ha/hook"
        )
        val view = SettingsView(
            settings, channelCount = 812, hiddenFilters = "UK,US", jellyfinSignedIn = true,
            profiles = listOf(Profile("default", "Main"), Profile("p2", "Kids")), activeProfileId = "p2", pinSet = true
        )
        val json = CompanionJson.settingsJson(view)
        val live = json.getJSONObject("live")
        assertEquals("http://panel", live.getString("server"))
        assertEquals("u", live.getString("username"))
        assertEquals("p", live.getString("password"))
        assertEquals("http://list", live.getString("playlist"))
        assertEquals("http://guide", live.getString("guide"))
        assertEquals("http://portal", live.getString("stalker_portal"))
        assertEquals("00:1A:79:12:34:56", live.getString("stalker_mac"))
        assertTrue(live.getBoolean("connected"))
        assertEquals(812, live.getInt("channel_count"))
        assertEquals(99L, live.getLong("subscription_expiry"))
        assertEquals("http://jf", json.getJSONObject("library").getString("server"))
        assertTrue(json.getJSONObject("library").getBoolean("signed_in"))
        val channels = json.getJSONObject("channels")
        assertEquals("UK,US", channels.getString("hidden"))
        assertEquals("ADULT", channels.getString("locked"))
        assertTrue(channels.getBoolean("strip_prefixes"))
        assertEquals("http://ha/hook", json.getJSONObject("home_assistant").getString("webhook"))
        val profiles = json.getJSONArray("profiles")
        assertEquals("default", profiles.getJSONObject(0).getString("id"))
        assertFalse(profiles.getJSONObject(0).getBoolean("active"))
        assertTrue(profiles.getJSONObject(1).getBoolean("active"))
        assertTrue(json.getBoolean("pin_set"))
    }

    @Test
    fun anEmptyTvIsNotConnected() {
        val view = SettingsView(Settings(), 0, "", false, listOf(Profile("default", "Main")), "default", false)
        val json = CompanionJson.settingsJson(view)
        assertFalse(json.getJSONObject("live").getBoolean("connected"))
        assertFalse(json.getJSONObject("library").getBoolean("signed_in"))
        assertFalse(json.getBoolean("pin_set"))
    }
}
