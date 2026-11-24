package com.dallas.cabletv.data.companion.client

import com.dallas.cabletv.data.companion.CompanionProtocol
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteAndSettingsTest {

    @Test
    fun keysGoThroughTheAndroidTvRemoteWhenItIsConnected() {
        assertEquals(KeyRoute.Atv(19), routeKey(RemoteKey.UP, atvConnected = true))
        assertEquals(KeyRoute.Atv(26), routeKey(RemoteKey.POWER, atvConnected = true))
        assertEquals(KeyRoute.Atv(3), routeKey(RemoteKey.HOME, atvConnected = true))
        assertEquals(KeyRoute.Atv(229), routeKey(RemoteKey.LAST, atvConnected = true))
    }

    @Test
    fun withoutItKeysUseTheCompanionConnectionAndTvOnlyKeysAreUnavailable() {
        assertEquals(KeyRoute.Cable("DPAD_CENTER"), routeKey(RemoteKey.OK, atvConnected = false))
        assertEquals(KeyRoute.Cable("5"), routeKey(RemoteKey.digit(5), atvConnected = false))
        for (key in listOf(RemoteKey.HOME, RemoteKey.POWER, RemoteKey.VOLUME_UP, RemoteKey.VOLUME_DOWN, RemoteKey.MUTE)) {
            assertEquals(KeyRoute.Unavailable, routeKey(key, atvConnected = false))
        }
    }

    @Test
    fun everyCompanionKeyNameIsOneTheTvAcceptsWithTheSameAndroidCode() {
        val keys = listOf(
            RemoteKey.UP, RemoteKey.DOWN, RemoteKey.LEFT, RemoteKey.RIGHT, RemoteKey.OK, RemoteKey.BACK,
            RemoteKey.GUIDE, RemoteKey.INFO, RemoteKey.LAST, RemoteKey.CHANNEL_UP, RemoteKey.CHANNEL_DOWN,
            RemoteKey.PLAY_PAUSE
        ) + (0..9).map { RemoteKey.digit(it) }
        for (key in keys) {
            val name = key.companionName!!
            assertEquals(name, CompanionProtocol.REMOTE_KEYS[name], key.atvCode)
        }
    }

    @Test
    fun digitsMapToKeycodes7To16() {
        assertEquals(7, RemoteKey.digit(0).atvCode)
        assertEquals(16, RemoteKey.digit(9).atvCode)
        assertEquals("0", RemoteKey.digit(0).companionName)
    }

    private val sample = """{
      "live": {"server": "http://s", "username": "u", "password": "p", "playlist": "pl", "guide": "g",
               "stalker_portal": "sp", "stalker_mac": "00:1A:79:00:00:01", "connected": true,
               "channel_count": 812, "subscription_expiry": 1790000000},
      "library": {"server": "http://jf", "signed_in": true},
      "channels": {"hidden": "XXX", "locked": "Adult", "strip_prefixes": true},
      "home_assistant": {"webhook": "http://ha/hook"},
      "profiles": [{"id": "main", "name": "Main", "active": false}, {"id": "kids", "name": "Kids", "active": true}],
      "pin_set": true}"""

    @Test
    fun settingsAreReadFromTheDocumentedShape() {
        val s = TvSettings.fromJson(JSONObject(sample))
        assertEquals("http://s", s.live.server)
        assertEquals("p", s.live.password)
        assertEquals("00:1A:79:00:00:01", s.live.stalkerMac)
        assertTrue(s.live.connected)
        assertEquals(812, s.live.channelCount)
        assertEquals(1790000000L, s.live.subscriptionExpiry)
        assertTrue(s.library.signedIn)
        assertEquals("XXX", s.channels.hidden)
        assertEquals("Adult", s.channels.locked)
        assertTrue(s.channels.stripPrefixes)
        assertEquals("http://ha/hook", s.webhook)
        assertEquals("Kids", s.activeProfileName)
        assertTrue(s.pinSet)
    }

    @Test
    fun anEmptyObjectReadsAsEmptyOffSettings() {
        val s = TvSettings.fromJson(JSONObject())
        assertEquals("", s.live.server)
        assertFalse(s.live.connected)
        assertFalse(s.library.signedIn)
        assertFalse(s.channels.stripPrefixes)
        assertNull(s.activeProfileName)
        assertFalse(s.pinSet)
    }

    @Test
    fun eachSaveSendsOnlyItsOwnSection() {
        val live = SettingsChanges.live(" http://s ", " u ", " p w ", "", "", "", "")
        assertEquals(listOf("live"), live.keys().asSequence().toList())
        assertEquals("http://s", live.getJSONObject("live").getString("server"))
        assertEquals(" p w ", live.getJSONObject("live").getString("password"))

        val signIn = SettingsChanges.librarySignIn("http://jf", "me", "pw")
        assertEquals("me", signIn.getJSONObject("library").getString("username"))
        assertTrue(SettingsChanges.librarySignOut().getJSONObject("library").getBoolean("sign_out"))

        val channels = SettingsChanges.channels("a\nb", "Adult", true).getJSONObject("channels")
        assertEquals("a\nb", channels.getString("hidden"))
        assertTrue(channels.getBoolean("strip_prefixes"))

        assertEquals("http://ha", SettingsChanges.webhook(" http://ha ").getJSONObject("home_assistant").getString("webhook"))
    }

    @Test
    fun savedReplyCarriesMessagesAndTheNewSettings() {
        val reply = JSONObject("""{"type":"saved","messages":["Live TV is connected.",""],"settings":$sample}""")
        val saved = SavedReply.fromJson(reply)
        assertEquals(listOf("Live TV is connected."), saved.messages)
        assertEquals("Kids", saved.settings!!.activeProfileName)
        assertNull(SavedReply.fromJson(JSONObject("""{"type":"saved"}""")).settings)
    }
}
