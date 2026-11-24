package com.dallas.cabletv.data.companion.server

import com.dallas.cabletv.data.LiveChannel
import com.dallas.cabletv.data.NowPlayingInfo
import com.dallas.cabletv.data.Profile
import com.dallas.cabletv.data.Settings
import com.dallas.cabletv.data.companion.CompanionProtocol
import com.dallas.cabletv.data.nowPlayingJson
import org.json.JSONArray
import org.json.JSONObject

/** One favorite channel as `get_favorites` lists it. [number] is 0 when the channel has none. */
data class FavoriteEntry(val id: String, val name: String, val number: Int, val logo: String?)

/** What `get_settings` reports, gathered by the app. */
data class SettingsView(
    val settings: Settings,
    /** Channels in the list after hiding, as Settings, Live TV shows the count. */
    val channelCount: Int,
    /** The active profile's hidden filters, the same text Settings, Channel list shows. */
    val hiddenFilters: String,
    /** True when the active profile has a Jellyfin login, its own or the household one. */
    val jellyfinSignedIn: Boolean,
    val profiles: List<Profile>,
    val activeProfileId: String,
    val pinSet: Boolean
)

/** The JSON the TV sends: what is playing, favorites and settings. Plain Kotlin, so tests run it on the JVM. */
object CompanionJson {

    /**
     * The `state` message: the screen, then what is playing exactly as Home Assistant hears it.
     * It is built from the same function as the Home Assistant message, so the two cannot drift
     * apart. Only `event` and `device` are left out.
     */
    fun stateMessage(screen: String, playState: String, info: NowPlayingInfo?): JSONObject {
        val playing = JSONObject(nowPlayingJson("", playState, info))
        playing.remove("event")
        playing.remove("device")
        val message = JSONObject().put("type", CompanionProtocol.Types.STATE).put("screen", screen)
        val keys = playing.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            message.put(key, playing.get(key))
        }
        return message
    }

    /**
     * The favorites that are in [channels], one entry per channel. Sorted the way Home Assistant's
     * favorites are: by channel number with unnumbered channels last, then by name.
     */
    fun favoriteEntries(channels: List<LiveChannel>, favoriteIds: Set<String>): List<FavoriteEntry> =
        channels.asSequence()
            .filter { it.id in favoriteIds && it.name.isNotBlank() }
            .distinctBy { it.id }
            .map { FavoriteEntry(it.id, it.name.trim(), it.number.coerceAtLeast(0), it.logo?.takeIf { logo -> logo.isNotBlank() }) }
            .sortedWith(compareBy<FavoriteEntry>({ it.number <= 0 }, { it.number }, { it.name.lowercase() }))
            .toList()

    fun favoritesJson(entries: List<FavoriteEntry>): JSONArray {
        val list = JSONArray()
        entries.forEach { f ->
            val item = JSONObject().put("id", f.id).put("name", f.name).put("number", f.number)
            f.logo?.let { item.put("logo", it) }
            list.put(item)
        }
        return list
    }

    /** The `settings` object of `get_settings` and `saved`. */
    fun settingsJson(view: SettingsView): JSONObject {
        val s = view.settings
        val live = JSONObject()
            .put("server", s.xtreamServer)
            .put("username", s.xtreamUser)
            .put("password", s.xtreamPass)
            .put("playlist", s.m3uUrl)
            .put("guide", s.epgUrl)
            .put("stalker_portal", s.stalkerUrl)
            .put("stalker_mac", s.stalkerMac)
            .put("connected", s.hasLiveSource)
            .put("channel_count", view.channelCount)
            .put("subscription_expiry", s.subscriptionExpiryMs)
        val library = JSONObject().put("server", s.jellyfinServer).put("signed_in", view.jellyfinSignedIn)
        val channels = JSONObject()
            .put("hidden", view.hiddenFilters)
            .put("locked", s.lockedFilters)
            .put("strip_prefixes", s.stripPrefixes)
        val profiles = JSONArray()
        view.profiles.forEach {
            profiles.put(JSONObject().put("id", it.id).put("name", it.name).put("active", it.id == view.activeProfileId))
        }
        return JSONObject()
            .put("live", live)
            .put("library", library)
            .put("channels", channels)
            .put("home_assistant", JSONObject().put("webhook", s.homeAssistantWebhook))
            .put("profiles", profiles)
            .put("pin_set", view.pinSet)
    }
}
