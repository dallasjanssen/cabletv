package com.dallas.cabletv.data.companion.client

import org.json.JSONObject

/** Settings, Live TV, as the TV reports them. */
data class LiveSettings(
    val server: String,
    val username: String,
    val password: String,
    val playlist: String,
    val guide: String,
    val stalkerPortal: String,
    val stalkerMac: String,
    val connected: Boolean,
    val channelCount: Int,
    val subscriptionExpiry: Long
)

/** Settings, Movies & Shows. */
data class LibrarySettings(val server: String, val signedIn: Boolean)

/** Settings, Channel list. [hidden] belongs to the active profile. */
data class ChannelSettings(val hidden: String, val locked: String, val stripPrefixes: Boolean)

data class TvProfile(val id: String, val name: String, val active: Boolean)

/** What `get_settings` and `saved` carry. */
data class TvSettings(
    val live: LiveSettings,
    val library: LibrarySettings,
    val channels: ChannelSettings,
    val webhook: String,
    val profiles: List<TvProfile>,
    val pinSet: Boolean
) {
    val activeProfileName: String? get() = profiles.firstOrNull { it.active }?.name

    companion object {
        /** Reads the `settings` object. Anything missing reads as empty, off or zero. */
        fun fromJson(o: JSONObject): TvSettings {
            val live = o.optJSONObject("live") ?: JSONObject()
            val library = o.optJSONObject("library") ?: JSONObject()
            val channels = o.optJSONObject("channels") ?: JSONObject()
            val ha = o.optJSONObject("home_assistant") ?: JSONObject()
            val profiles = o.optJSONArray("profiles")
            val profileList = if (profiles == null) emptyList() else (0 until profiles.length()).mapNotNull { i ->
                profiles.optJSONObject(i)?.let {
                    TvProfile(it.optString("id"), it.optString("name"), it.optBoolean("active", false))
                }
            }
            return TvSettings(
                live = LiveSettings(
                    server = live.optString("server"),
                    username = live.optString("username"),
                    password = live.optString("password"),
                    playlist = live.optString("playlist"),
                    guide = live.optString("guide"),
                    stalkerPortal = live.optString("stalker_portal"),
                    stalkerMac = live.optString("stalker_mac"),
                    connected = live.optBoolean("connected", false),
                    channelCount = live.optInt("channel_count", 0),
                    subscriptionExpiry = live.optLong("subscription_expiry", 0L)
                ),
                library = LibrarySettings(library.optString("server"), library.optBoolean("signed_in", false)),
                channels = ChannelSettings(
                    hidden = channels.optString("hidden"),
                    locked = channels.optString("locked"),
                    stripPrefixes = channels.optBoolean("strip_prefixes", false)
                ),
                webhook = ha.optString("webhook"),
                profiles = profileList,
                pinSet = o.optBoolean("pin_set", false)
            )
        }
    }
}

/** The `settings` object for each save button. A save sends only its own section. */
object SettingsChanges {
    fun live(
        server: String,
        username: String,
        password: String,
        playlist: String,
        guide: String,
        stalkerPortal: String,
        stalkerMac: String
    ): JSONObject = JSONObject().put(
        "live",
        JSONObject()
            .put("server", server.trim())
            .put("username", username.trim())
            .put("password", password)
            .put("playlist", playlist.trim())
            .put("guide", guide.trim())
            .put("stalker_portal", stalkerPortal.trim())
            .put("stalker_mac", stalkerMac.trim())
    )

    fun librarySignIn(server: String, username: String, password: String): JSONObject = JSONObject().put(
        "library",
        JSONObject().put("server", server.trim()).put("username", username.trim()).put("password", password)
    )

    fun librarySignOut(): JSONObject = JSONObject().put("library", JSONObject().put("sign_out", true))

    fun channels(hidden: String, locked: String, stripPrefixes: Boolean): JSONObject = JSONObject().put(
        "channels",
        JSONObject().put("hidden", hidden).put("locked", locked).put("strip_prefixes", stripPrefixes)
    )

    fun webhook(url: String): JSONObject =
        JSONObject().put("home_assistant", JSONObject().put("webhook", url.trim()))
}

/** What the TV answered to a save: the messages it would show, and the settings as they are now. */
data class SavedReply(val messages: List<String>, val settings: TvSettings?) {
    companion object {
        fun fromJson(reply: JSONObject): SavedReply = SavedReply(
            messages = reply.stringList("messages"),
            settings = reply.optJSONObject("settings")?.let { TvSettings.fromJson(it) }
        )
    }
}
