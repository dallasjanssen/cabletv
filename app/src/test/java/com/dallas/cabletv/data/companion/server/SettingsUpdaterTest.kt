package com.dallas.cabletv.data.companion.server

import com.dallas.cabletv.data.Settings
import com.dallas.cabletv.data.companion.CompanionProtocol.Errors
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SettingsUpdaterTest {
    private class LiveCall(val server: String, val user: String, val pass: String, val m3u: String, val su: String, val sm: String, val epg: String)

    private val liveCalls = mutableListOf<LiveCall>()
    private val signInCalls = mutableListOf<List<String>>()
    private var liveFailure: String? = null
    private var signInFailure: String? = null

    private val updater = SettingsUpdater(
        checkLive = { current, server, user, pass, m3u, su, sm, epg ->
            liveCalls += LiveCall(server, user, pass, m3u, su, sm, epg)
            liveFailure?.let { throw IllegalArgumentException(it) }
            current.copy(xtreamServer = server, xtreamUser = user, xtreamPass = pass, m3uUrl = m3u, epgUrl = epg, stalkerUrl = su, stalkerMac = sm)
        },
        signIn = { current, server, user, pass ->
            signInCalls += listOf(server, user, pass)
            signInFailure?.let { throw IllegalStateException(it) }
            current.copy(jellyfinServer = server, jellyfinToken = "token", jellyfinUserId = "uid")
        }
    )

    private val saved = Settings(
        xtreamServer = "http://old", xtreamUser = "olduser", xtreamPass = "oldpass", m3uUrl = "http://oldlist",
        epgUrl = "http://oldguide", jellyfinServer = "http://jf", jellyfinToken = "oldtoken", jellyfinUserId = "olduid"
    )

    private fun plan(json: String): SettingsChange = runBlocking { updater.plan(saved, JSONObject(json)) }

    private fun planFails(json: String): CompanionException {
        try {
            plan(json)
        } catch (e: CompanionException) {
            return e
        }
        fail("Expected the request to be refused")
        throw IllegalStateException()
    }

    @Test
    fun anEmptyRequestChangesNothing() {
        val change = plan("{}")
        assertEquals(saved, change.settings)
        assertNull(change.hiddenFilters)
        assertNull(change.jellyfinSignIn)
        assertFalse(change.jellyfinSignOut)
        assertTrue(change.messages.isEmpty())
        assertTrue(liveCalls.isEmpty())
    }

    @Test
    fun liveFieldsThatAreMissingComeFromWhatIsSaved() {
        val change = plan("""{"live": {"username": "newuser", "password": "newpass"}}""")
        val call = liveCalls.single()
        assertEquals("http://old", call.server)
        assertEquals("newuser", call.user)
        assertEquals("newpass", call.pass)
        assertEquals("http://oldlist", call.m3u)
        assertEquals("http://oldguide", call.epg)
        assertEquals("newuser", change.settings.xtreamUser)
        assertEquals(listOf("Live TV is connected."), change.messages)
    }

    @Test
    fun aFieldSentEmptyClearsIt() {
        plan("""{"live": {"playlist": "", "guide": ""}}""")
        val call = liveCalls.single()
        assertEquals("", call.m3u)
        assertEquals("", call.epg)
        assertEquals("http://old", call.server)
    }

    @Test
    fun aFailedLiveCheckIsInvalidWithTheTvsOwnMessageAndNothingElseApplies() {
        liveFailure = "The server said the account is not active."
        val e = planFails("""{"live": {"server": "http://x"}, "channels": {"hidden": "UK"}, "library": {"server": "http://jf2", "username": "me"}}""")
        assertEquals(Errors.INVALID, e.code)
        assertEquals("The server said the account is not active.", e.message)
        // The library step never ran, so no sign in happened.
        assertTrue(signInCalls.isEmpty())
    }

    @Test
    fun theLibraryStartsFromWhatTheLiveStepSaved() {
        val change = plan("""{"live": {"server": "http://new"}, "library": {"server": "http://jf2", "username": "me", "password": "pw"}}""")
        assertEquals(listOf("http://jf2", "me", "pw"), signInCalls.single())
        // Jellyfin is saved by its own rules, so the settings keep the old Jellyfin fields.
        assertEquals("http://new", change.settings.xtreamServer)
        assertEquals("http://jf", change.settings.jellyfinServer)
        assertEquals("oldtoken", change.settings.jellyfinToken)
        val signedIn = change.jellyfinSignIn!!
        assertEquals("http://jf2", signedIn.jellyfinServer)
        assertEquals("token", signedIn.jellyfinToken)
        assertEquals("uid", signedIn.jellyfinUserId)
        assertEquals("http://new", signedIn.xtreamServer)
        assertEquals(listOf("Live TV is connected.", "Jellyfin is signed in."), change.messages)
    }

    @Test
    fun aFailedSignInIsInvalid() {
        signInFailure = "wrong password"
        val e = planFails("""{"library": {"server": "http://jf2", "username": "me", "password": "bad"}}""")
        assertEquals(Errors.INVALID, e.code)
        assertEquals("Sign in failed. wrong password", e.message)
    }

    @Test
    fun signingOutOfTheLibrary() {
        val change = plan("""{"library": {"sign_out": true}}""")
        assertTrue(change.jellyfinSignOut)
        assertNull(change.jellyfinSignIn)
        assertTrue(signInCalls.isEmpty())
        assertEquals(listOf("Signed out of Jellyfin for this profile."), change.messages)
    }

    @Test
    fun channelSettings() {
        val change = plan("""{"channels": {"hidden": " UK, US ", "locked": "ADULT ", "strip_prefixes": true}}""")
        assertEquals("UK, US", change.hiddenFilters)
        assertEquals("ADULT", change.settings.lockedFilters)
        assertTrue(change.settings.stripPrefixes)
        // The household hidden list is not touched. The hidden filters belong to the active profile.
        assertEquals(saved.hiddenFilters, change.settings.hiddenFilters)
        assertEquals(listOf("Customizations saved."), change.messages)
    }

    @Test
    fun channelSettingsAreOptionalOneByOne() {
        val change = plan("""{"channels": {"strip_prefixes": true}}""")
        assertNull(change.hiddenFilters)
        assertEquals(saved.lockedFilters, change.settings.lockedFilters)
        assertTrue(change.settings.stripPrefixes)
    }

    @Test
    fun theWebhookIsNormalizedAndChecked() {
        val change = plan("""{"home_assistant": {"webhook": "192.168.1.5:8123/api/webhook/abc"}}""")
        assertEquals("http://192.168.1.5:8123/api/webhook/abc", change.settings.homeAssistantWebhook)
        assertEquals(listOf("Customizations saved."), change.messages)

        val cleared = plan("""{"home_assistant": {"webhook": ""}}""")
        assertEquals("", cleared.settings.homeAssistantWebhook)

        val e = planFails("""{"home_assistant": {"webhook": "ftp://nope"}}""")
        assertEquals(Errors.INVALID, e.code)
    }

    @Test
    fun customizationsAreReportedOnce() {
        val change = plan("""{"channels": {"locked": "X"}, "home_assistant": {"webhook": "http://ha"}}""")
        assertEquals(listOf("Customizations saved."), change.messages)
    }
}
