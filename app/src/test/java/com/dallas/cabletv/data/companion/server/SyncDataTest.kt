package com.dallas.cabletv.data.companion.server

import com.dallas.cabletv.data.Profile
import com.dallas.cabletv.data.Settings
import com.dallas.cabletv.data.companion.CompanionProtocol.Errors
import com.dallas.cabletv.data.companion.CompanionProtocol.Sections
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** A TV's stores, in memory. */
private class FakeTv(
    var settings: Settings = Settings(),
    val profiles: MutableList<Profile> = mutableListOf(Profile("default", "Main"))
) : SyncTarget {
    val hidden = mutableMapOf<String, String?>()
    val favs = mutableMapOf<String, Set<String>>()
    var pin = ""
    var saves = 0
    private var nextId = 1

    override fun settings() = settings
    override fun saveSettings(settings: Settings) { this.settings = settings; saves++ }
    override fun profiles(): List<Profile> = profiles.toList()
    override fun addProfile(name: String): Profile? = Profile("p${nextId++}", name).also { profiles += it }
    override fun hiddenFilters(profileId: String): String? = hidden[profileId]
    override fun setHiddenFilters(profileId: String, value: String?) { hidden[profileId] = value }
    override fun favorites(profileId: String): Set<String> = favs[profileId].orEmpty()
    override fun setFavorites(profileId: String, ids: Set<String>) { favs[profileId] = ids }
    override fun pinRecord() = pin
    override fun setPinRecord(record: String) { pin = record }
}

class SyncDataTest {
    private val record = "00112233445566778899aabbccddeeff:" + "ab".repeat(32)

    private fun data(sections: String): JSONObject = JSONObject("""{"format": 1, "source_tv": "Other", "sections": $sections}""")

    private fun sourceTv(): FakeTv {
        val tv = FakeTv(
            Settings(
                xtreamServer = "http://panel", xtreamUser = "u", xtreamPass = "p", m3uUrl = "http://list", epgUrl = "http://guide",
                stalkerUrl = "http://portal", stalkerMac = "00:1A:79:12:34:56", jellyfinServer = "http://jf", jellyfinToken = "tok",
                jellyfinUserId = "uid", subscriptionExpiryMs = 12345L, hiddenFilters = "UK,US", stripPrefixes = true,
                lockedFilters = "ADULT", homeAssistantWebhook = "http://ha/hook"
            ),
            mutableListOf(Profile("default", "Main"), Profile("kid", "Kids"))
        )
        tv.hidden["kid"] = "NEWS"
        tv.favs["default"] = setOf("2", "1")
        tv.favs["kid"] = setOf("9")
        tv.pin = record
        return tv
    }

    @Test
    fun exportHasEverySectionTheSpecNames() {
        val out = SyncData(sourceTv(), "Living Room TV").export(Sections.ALL)
        assertEquals(1, out.getInt("format"))
        assertEquals("Living Room TV", out.getString("source_tv"))
        val sections = out.getJSONObject("sections")

        val logins = sections.getJSONObject("logins")
        assertEquals("http://panel", logins.getString("xtream_server"))
        assertEquals("u", logins.getString("xtream_user"))
        assertEquals("p", logins.getString("xtream_pass"))
        assertEquals("http://list", logins.getString("m3u_url"))
        assertEquals("http://guide", logins.getString("epg_url"))
        assertEquals("http://portal", logins.getString("stalker_url"))
        assertEquals("00:1A:79:12:34:56", logins.getString("stalker_mac"))
        assertEquals("http://jf", logins.getString("jellyfin_server"))
        assertEquals("tok", logins.getString("jellyfin_token"))
        assertEquals("uid", logins.getString("jellyfin_user_id"))
        assertEquals(12345L, logins.getLong("subscription_expiry"))

        val channels = sections.getJSONObject("channels")
        assertEquals("UK,US", channels.getString("hidden_filters"))
        assertTrue(channels.getBoolean("strip_prefixes"))
        val profiles = channels.getJSONArray("profiles")
        assertEquals("Main", profiles.getJSONObject(0).getString("name"))
        // A profile that uses the household list says null, not an empty list.
        assertTrue(profiles.getJSONObject(0).isNull("hidden_filters"))
        assertEquals("NEWS", profiles.getJSONObject(1).getString("hidden_filters"))

        val favorites = sections.getJSONObject("favorites").getJSONArray("profiles")
        assertEquals("Main", favorites.getJSONObject(0).getString("name"))
        assertEquals(listOf("1", "2"), (0 until 2).map { favorites.getJSONObject(0).getJSONArray("favorites").getString(it) })

        val lock = sections.getJSONObject("lock")
        assertEquals(record, lock.getString("pin_record"))
        assertEquals("ADULT", lock.getString("locked_filters"))
    }

    @Test
    fun exportHasOnlyTheSectionsAskedFor() {
        val sections = SyncData(sourceTv(), "TV").export(listOf(Sections.FAVORITES, Sections.LOCK)).getJSONObject("sections")
        assertEquals(setOf("favorites", "lock"), sections.keys().asSequence().toSet())
    }

    @Test
    fun theWebhookNeverTravels() {
        val text = SyncData(sourceTv(), "TV").export(Sections.ALL).toString()
        assertFalse(text.contains("http://ha/hook"))
        assertFalse(text.contains("webhook"))
    }

    @Test
    fun loginsReplaceThoseSettingsAndKeepTheRest() {
        val target = FakeTv(Settings(xtreamServer = "http://old", homeAssistantWebhook = "http://mine", hiddenFilters = "KEEP", lockedFilters = "KEEP2"))
        val messages = SyncData(target, "TV").import(
            data("""{"logins": {"xtream_server": "http://panel", "xtream_user": "u", "xtream_pass": " p ", "jellyfin_token": "tok", "subscription_expiry": 5}}""")
        )
        val s = target.settings
        assertEquals("http://panel", s.xtreamServer)
        assertEquals("u", s.xtreamUser)
        // A password keeps its spaces.
        assertEquals(" p ", s.xtreamPass)
        assertEquals("tok", s.jellyfinToken)
        assertEquals(5L, s.subscriptionExpiryMs)
        assertEquals("", s.m3uUrl)
        assertEquals("http://mine", s.homeAssistantWebhook)
        assertEquals("KEEP", s.hiddenFilters)
        assertEquals("KEEP2", s.lockedFilters)
        assertEquals(1, target.saves)
        assertEquals(listOf("Logins copied. Channels are loading."), messages)
    }

    @Test
    fun channelsSetTheHouseholdListAndThePrefixSetting() {
        val target = FakeTv()
        SyncData(target, "TV").import(data("""{"channels": {"hidden_filters": " UK ", "strip_prefixes": true, "profiles": []}}"""))
        assertEquals("UK", target.settings.hiddenFilters)
        assertTrue(target.settings.stripPrefixes)
        assertEquals(1, target.profiles.size)
    }

    @Test
    fun profilesAreMatchedByNameIgnoringCaseAndMissingOnesAreCreated() {
        val target = FakeTv(profiles = mutableListOf(Profile("default", "Main"), Profile("kid", "Kids")))
        target.hidden["kid"] = "OLD"
        target.hidden["default"] = "ALSO OLD"
        SyncData(target, "TV").import(
            data(
                """{"channels": {"profiles": [
                    {"name": "main", "hidden_filters": "UK"},
                    {"name": " KIDS ", "hidden_filters": null},
                    {"name": "Guests", "hidden_filters": "XXX"}
                ]}}"""
            )
        )
        assertEquals(3, target.profiles.size)
        assertEquals("UK", target.hidden["default"])
        // Null clears a profile's own filters, so it uses the household ones again.
        assertNull(target.hidden["kid"])
        assertTrue(target.hidden.containsKey("kid"))
        val guests = target.profiles.single { it.name == "Guests" }
        assertEquals("XXX", target.hidden[guests.id])
    }

    @Test
    fun aProfileWithNoHiddenFiltersKeyIsLeftAlone() {
        val target = FakeTv()
        target.hidden["default"] = "MINE"
        SyncData(target, "TV").import(data("""{"channels": {"profiles": [{"name": "Main"}]}}"""))
        assertEquals("MINE", target.hidden["default"])
    }

    @Test
    fun blankProfileNamesAreSkipped() {
        val target = FakeTv()
        SyncData(target, "TV").import(data("""{"channels": {"profiles": [{"name": "  ", "hidden_filters": "X"}]}, "favorites": {"profiles": [{"name": "", "favorites": ["1"]}]}}"""))
        assertEquals(1, target.profiles.size)
        assertTrue(target.hidden.isEmpty())
        assertTrue(target.favs.isEmpty())
    }

    @Test
    fun favoritesReplaceEachProfilesFavoritesAndCreateMissingProfiles() {
        val target = FakeTv()
        target.favs["default"] = setOf("old")
        SyncData(target, "TV").import(
            data("""{"favorites": {"profiles": [{"name": "MAIN", "favorites": ["1", " 2 ", "", "1"]}, {"name": "Grandma", "favorites": ["7"]}]}}""")
        )
        assertEquals(setOf("1", "2"), target.favs["default"])
        val grandma = target.profiles.single { it.name == "Grandma" }
        assertEquals(setOf("7"), target.favs[grandma.id])
    }

    @Test
    fun anEmptyFavoritesListClearsThem() {
        val target = FakeTv()
        target.favs["default"] = setOf("1")
        SyncData(target, "TV").import(data("""{"favorites": {"profiles": [{"name": "Main", "favorites": []}]}}"""))
        assertEquals(emptySet<String>(), target.favs["default"])
    }

    @Test
    fun theLockSetsThePinRecordAndTheLockedGroups() {
        val target = FakeTv()
        SyncData(target, "TV").import(data("""{"lock": {"pin_record": "$record", "locked_filters": "ADULT, XXX"}}"""))
        assertEquals(record, target.pin)
        assertEquals("ADULT, XXX", target.settings.lockedFilters)
    }

    @Test
    fun anEmptyPinRecordRemovesThePin() {
        val target = FakeTv()
        target.pin = record
        SyncData(target, "TV").import(data("""{"lock": {"pin_record": "", "locked_filters": ""}}"""))
        assertEquals("", target.pin)
    }

    @Test
    fun aLockWithoutAPinRecordLeavesThePinAlone() {
        val target = FakeTv()
        target.pin = record
        SyncData(target, "TV").import(data("""{"lock": {"locked_filters": "X"}}"""))
        assertEquals(record, target.pin)
        assertEquals("X", target.settings.lockedFilters)
    }

    @Test
    fun aPinRecordThatIsNotAHashStopsTheWholeImport() {
        val target = FakeTv()
        try {
            SyncData(target, "TV").import(data("""{"logins": {"xtream_server": "http://panel"}, "lock": {"pin_record": "1234"}}"""))
            fail("Expected the import to be refused")
        } catch (e: CompanionException) {
            assertEquals(Errors.INVALID, e.code)
        }
        assertEquals("", target.settings.xtreamServer)
        assertEquals("", target.pin)
        assertEquals(0, target.saves)
    }

    @Test
    fun sectionsThatAreNotInTheFileAreNotTouched() {
        val target = FakeTv(Settings(xtreamServer = "http://keep", lockedFilters = "KEEP"))
        target.pin = record
        target.favs["default"] = setOf("1")
        val messages = SyncData(target, "TV").import(data("{}"))
        assertTrue(messages.isEmpty())
        assertEquals("http://keep", target.settings.xtreamServer)
        assertEquals(record, target.pin)
        assertEquals(setOf("1"), target.favs["default"])
        assertEquals(0, target.saves)
    }

    @Test
    fun aFullExportImportsIntoAnotherTv() {
        val source = sourceTv()
        val file = SyncData(source, "Source").export(Sections.ALL)
        val target = FakeTv(Settings(homeAssistantWebhook = "http://target-ha"), mutableListOf(Profile("default", "main")))
        SyncData(target, "Target").import(file)

        assertEquals(source.settings.copy(homeAssistantWebhook = "http://target-ha"), target.settings)
        assertEquals(listOf("main", "Kids"), target.profiles.map { it.name })
        assertEquals(setOf("1", "2"), target.favs["default"])
        val kids = target.profiles.single { it.name == "Kids" }
        assertEquals(setOf("9"), target.favs[kids.id])
        assertEquals("NEWS", target.hidden[kids.id])
        assertNull(target.hidden["default"])
        assertEquals(record, target.pin)
    }

    @Test
    fun matchProfileIgnoresCaseAndSpaces() {
        val list = listOf(Profile("a", "Main"), Profile("b", "Kids"))
        assertEquals("b", SyncData.matchProfile(list, " kIDS ")?.id)
        assertNull(SyncData.matchProfile(list, "Guests"))
    }

    @Test
    fun exportedJsonIsValidAndReadable() {
        val text = SyncData(sourceTv(), "TV").export(Sections.ALL).toString()
        val back = JSONObject(text)
        assertTrue(back.getJSONObject("sections").getJSONObject("favorites").getJSONArray("profiles") is JSONArray)
    }
}
