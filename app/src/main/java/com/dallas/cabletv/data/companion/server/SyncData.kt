package com.dallas.cabletv.data.companion.server

import com.dallas.cabletv.data.Profile
import com.dallas.cabletv.data.Settings
import com.dallas.cabletv.data.companion.CompanionProtocol
import com.dallas.cabletv.data.companion.CompanionProtocol.Sections
import org.json.JSONArray
import org.json.JSONObject

/**
 * Everything sync needs from the TV. The real one reads and writes the saved stores and keeps the
 * app's state in step. Tests use a fake.
 */
interface SyncTarget {
    fun settings(): Settings

    /** Saves [settings] the way Settings does, so the channels reload when a login changed. */
    fun saveSettings(settings: Settings)

    fun profiles(): List<Profile>

    /** Adds a profile and returns it, or null when it cannot be added. */
    fun addProfile(name: String): Profile?

    /** The profile's own hidden filters, or null when it uses the household ones. */
    fun hiddenFilters(profileId: String): String?

    /** Sets the profile's own hidden filters. Null clears them, so the household ones apply. */
    fun setHiddenFilters(profileId: String, value: String?)

    fun favorites(profileId: String): Set<String>
    fun setFavorites(profileId: String, ids: Set<String>)

    /** The stored PIN as salt and hash text, or empty when there is no PIN. */
    fun pinRecord(): String

    /** Sets the stored PIN from salt and hash text. Empty removes the PIN. */
    fun setPinRecord(record: String)
}

/**
 * The sync file: what an export holds and how an import applies it. The rules are in
 * docs/companion-protocol.md. Profiles are matched by name, ignoring case, and a profile that is
 * missing on the target is created. Things that belong to one TV, such as the Home Assistant
 * webhook, are never part of it.
 */
class SyncData(private val target: SyncTarget, private val tvName: String) {

    /** The sync file with only the [sections] asked for. */
    fun export(sections: List<String>): JSONObject {
        val out = JSONObject()
        val s = target.settings()
        val profiles = target.profiles()
        if (Sections.LOGINS in sections) {
            out.put(
                Sections.LOGINS,
                JSONObject()
                    .put("xtream_server", s.xtreamServer)
                    .put("xtream_user", s.xtreamUser)
                    .put("xtream_pass", s.xtreamPass)
                    .put("m3u_url", s.m3uUrl)
                    .put("epg_url", s.epgUrl)
                    .put("stalker_url", s.stalkerUrl)
                    .put("stalker_mac", s.stalkerMac)
                    .put("jellyfin_server", s.jellyfinServer)
                    .put("jellyfin_token", s.jellyfinToken)
                    .put("jellyfin_user_id", s.jellyfinUserId)
                    .put("subscription_expiry", s.subscriptionExpiryMs)
            )
        }
        if (Sections.CHANNELS in sections) {
            val list = JSONArray()
            profiles.forEach { p ->
                list.put(JSONObject().put("name", p.name).put("hidden_filters", target.hiddenFilters(p.id) ?: JSONObject.NULL))
            }
            out.put(
                Sections.CHANNELS,
                JSONObject().put("hidden_filters", s.hiddenFilters).put("strip_prefixes", s.stripPrefixes).put("profiles", list)
            )
        }
        if (Sections.FAVORITES in sections) {
            val list = JSONArray()
            profiles.forEach { p ->
                list.put(JSONObject().put("name", p.name).put("favorites", JSONArray(target.favorites(p.id).sorted())))
            }
            out.put(Sections.FAVORITES, JSONObject().put("profiles", list))
        }
        if (Sections.LOCK in sections) {
            out.put(Sections.LOCK, JSONObject().put("pin_record", target.pinRecord()).put("locked_filters", s.lockedFilters))
        }
        return JSONObject().put("format", FORMAT).put("source_tv", tvName).put("sections", out)
    }

    /**
     * Applies each section present in [data] and returns the messages to show. A PIN that is not
     * a salt and hash record stops the whole import before anything changes.
     */
    fun import(data: JSONObject): List<String> {
        val sections = data.optJSONObject("sections") ?: return emptyList()
        val lock = sections.optJSONObject(Sections.LOCK)
        val record = lock?.takeIf { it.has("pin_record") }?.optString("pin_record")?.trim()
        if (record != null && record.isNotEmpty() && !PIN_RECORD.matches(record)) {
            throw CompanionException(CompanionProtocol.Errors.INVALID, "The parental lock in that copy is not valid.")
        }

        val messages = mutableListOf<String>()
        var settings = target.settings()

        sections.optJSONObject(Sections.LOGINS)?.let { logins ->
            settings = applyLogins(settings, logins)
            messages += "Logins copied. Channels are loading."
        }

        sections.optJSONObject(Sections.CHANNELS)?.let { channels ->
            if (channels.has("hidden_filters")) settings = settings.copy(hiddenFilters = channels.optString("hidden_filters").trim())
            if (channels.has("strip_prefixes")) settings = settings.copy(stripPrefixes = channels.optBoolean("strip_prefixes"))
            val list = channels.optJSONArray("profiles") ?: JSONArray()
            for (i in 0 until list.length()) {
                val entry = list.optJSONObject(i) ?: continue
                val profile = profileFor(entry.optString("name")) ?: continue
                if (!entry.has("hidden_filters")) continue
                val own = if (entry.isNull("hidden_filters")) null else entry.optString("hidden_filters").trim()
                target.setHiddenFilters(profile.id, own)
            }
            messages += "Channel customizations copied."
        }

        sections.optJSONObject(Sections.FAVORITES)?.let { favorites ->
            val list = favorites.optJSONArray("profiles") ?: JSONArray()
            for (i in 0 until list.length()) {
                val entry = list.optJSONObject(i) ?: continue
                val profile = profileFor(entry.optString("name")) ?: continue
                val ids = entry.optJSONArray("favorites") ?: continue
                target.setFavorites(profile.id, (0 until ids.length()).map { ids.optString(it).trim() }.filter { it.isNotEmpty() }.toSet())
            }
            messages += "Favorites copied."
        }

        if (lock != null) {
            if (lock.has("locked_filters")) settings = settings.copy(lockedFilters = lock.optString("locked_filters").trim())
            if (record != null) target.setPinRecord(record)
            messages += "Parental lock copied."
        }

        if (settings != target.settings()) target.saveSettings(settings)
        return messages
    }

    /** The target's profile called [name], made if it is missing. Null for a blank name. */
    private fun profileFor(name: String): Profile? {
        if (name.isBlank()) return null
        return matchProfile(target.profiles(), name) ?: target.addProfile(name.trim())
    }

    companion object {
        const val FORMAT = 1

        /** The stored PIN: a salt in hex, a colon, and a SHA-256 hash in hex. */
        private val PIN_RECORD = Regex("^[0-9a-f]+:[0-9a-f]{64}$")

        /** The profile called [name], ignoring case and spaces around it. */
        fun matchProfile(profiles: List<Profile>, name: String): Profile? =
            profiles.firstOrNull { it.name.trim().equals(name.trim(), ignoreCase = true) }

        /** [settings] with the logins in [logins] replacing the ones it has. Keys that are missing are left alone. */
        fun applyLogins(settings: Settings, logins: JSONObject): Settings {
            fun text(key: String, saved: String) = if (logins.has(key)) logins.optString(key).trim() else saved
            return settings.copy(
                xtreamServer = text("xtream_server", settings.xtreamServer),
                xtreamUser = text("xtream_user", settings.xtreamUser),
                xtreamPass = if (logins.has("xtream_pass")) logins.optString("xtream_pass") else settings.xtreamPass,
                m3uUrl = text("m3u_url", settings.m3uUrl),
                epgUrl = text("epg_url", settings.epgUrl),
                stalkerUrl = text("stalker_url", settings.stalkerUrl),
                stalkerMac = text("stalker_mac", settings.stalkerMac),
                jellyfinServer = text("jellyfin_server", settings.jellyfinServer),
                jellyfinToken = text("jellyfin_token", settings.jellyfinToken),
                jellyfinUserId = text("jellyfin_user_id", settings.jellyfinUserId),
                subscriptionExpiryMs = if (logins.has("subscription_expiry")) logins.optLong("subscription_expiry") else settings.subscriptionExpiryMs
            )
        }
    }
}
