package com.dallas.cabletv.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/** The logins that stream addresses carry. Kept out of the saved resume points. */
data class StreamCredentials(val xtreamUser: String, val xtreamPass: String, val jellyfinToken: String)

private const val PH_USER = "@@XU@@"
private const val PH_PASS = "@@XP@@"
private const val PH_TOKEN = "@@JT@@"

private val XTREAM_PATH = Regex("/(movie|series|live|timeshift)/([^/?#]+)/([^/?#]+)/")
private val QUERY_USER = Regex("([?&]username=)[^&#]*")
private val QUERY_PASS = Regex("([?&]password=)[^&#]*")
private val QUERY_TOKEN = Regex("([?&]api_key=)[^&#]*")

/** Replaces credentials in a stream or poster address with placeholders, by the shape of the address. */
fun maskStreamUrl(url: String): String =
    url.replace(XTREAM_PATH) { "/${it.groupValues[1]}/$PH_USER/$PH_PASS/" }
        .replace(QUERY_USER) { it.groupValues[1] + PH_USER }
        .replace(QUERY_PASS) { it.groupValues[1] + PH_PASS }
        .replace(QUERY_TOKEN) { it.groupValues[1] + PH_TOKEN }

private fun encPart(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

/** Puts the real credentials back. Without credentials the placeholders stay, so playback fails instead of leaking. */
fun fillStreamUrl(url: String, credentials: StreamCredentials?): String {
    if (credentials == null) return url
    return url.replace(PH_USER, encPart(credentials.xtreamUser))
        .replace(PH_PASS, encPart(credentials.xtreamPass))
        .replace(PH_TOKEN, credentials.jellyfinToken)
}

/** The preferences file that holds one profile's state. The default profile keeps the original name. */
fun userStatePrefsName(profileId: String): String =
    if (profileId == ProfileStore.DEFAULT_ID) "cabletv_user_state" else "cabletv_user_state_$profileId"

/**
 * Favorites, recently watched channels and on-demand resume points.
 * None of this is secret, so it lives in ordinary preferences.
 */
class UserStateStore(context: Context, profileId: String = ProfileStore.DEFAULT_ID) {

    // The default profile keeps the original file name, so nothing is lost when profiles arrived.
    private val prefs = context.getSharedPreferences(userStatePrefsName(profileId), Context.MODE_PRIVATE)

    /** Set by the app whenever the logins change. Used to fill resume addresses on read. */
    @Volatile var credentials: StreamCredentials? = null

    fun favorites(): Set<String> =
        try { prefs.getStringSet(K_FAV, emptySet())?.toSet() ?: emptySet() } catch (e: Exception) { emptySet() }

    /** Adds or removes a favorite. Returns true when the channel is now a favorite. */
    fun toggleFavorite(channelId: String): Boolean {
        val set = favorites().toMutableSet()
        val added = !set.remove(channelId)
        if (added) set.add(channelId)
        prefs.edit().putStringSet(K_FAV, set).apply()
        return added
    }

    /** Channel IDs, most recently watched first. */
    fun recents(): List<String> {
        return try {
            val arr = JSONArray(prefs.getString(K_RECENT, "[]") ?: "[]")
            (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotEmpty() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun addRecent(channelId: String) {
        val list = (listOf(channelId) + recents().filter { it != channelId }).take(MAX_RECENT)
        prefs.edit().putString(K_RECENT, JSONArray(list).toString()).apply()
    }

    /** Remembers where a title was stopped. Near the start or near the end, the position is forgotten. */
    fun saveResume(item: VodItem, positionMs: Long, durationMs: Long) {
        val all = resumeObject()
        val key = resumeKey(item)
        if (durationMs <= 0 || positionMs < 30_000L || positionMs > durationMs * 0.95) {
            all.remove(key)
        } else {
            all.put(
                key,
                JSONObject()
                    .put("id", item.id)
                    .put("name", item.name)
                    .put("year", item.year ?: JSONObject.NULL)
                    .put("poster", maskStreamUrl(item.posterUrl))
                    .put("url", maskStreamUrl(item.streamUrl))
                    .put("type", item.type)
                    .put("source", item.source)
                    .put("season", item.season ?: JSONObject.NULL)
                    .put("episode", item.episode ?: JSONObject.NULL)
                    .put("series", item.seriesName ?: JSONObject.NULL)
                    .put("seriesId", item.seriesId ?: JSONObject.NULL)
                    .put("pos", positionMs)
                    .put("dur", durationMs)
                    .put("at", System.currentTimeMillis())
            )
        }
        prefs.edit().putString(K_RESUME, all.toString()).apply()
    }

    /** The saved position for a title, or 0 when there is none. */
    fun resumePosition(item: VodItem): Long =
        resumeObject().optJSONObject(resumeKey(item))?.optLong("pos") ?: 0L

    /** Titles with a saved position, most recently watched first. */
    fun continueWatching(): List<Pair<VodItem, Long>> {
        val all = resumeObject()
        val entries = mutableListOf<Triple<Long, VodItem, Long>>()
        val keys = all.keys()
        while (keys.hasNext()) {
            val o = all.optJSONObject(keys.next()) ?: continue
            val item = VodItem(
                id = o.optString("id"),
                name = o.optString("name"),
                year = if (o.isNull("year")) null else o.optInt("year"),
                overview = null,
                posterUrl = fillStreamUrl(o.optString("poster"), credentials),
                streamUrl = fillStreamUrl(o.optString("url"), credentials),
                type = o.optString("type"),
                source = o.optString("source", "jellyfin"),
                season = if (o.isNull("season")) null else o.optInt("season").takeIf { it > 0 },
                episode = if (o.isNull("episode")) null else o.optInt("episode").takeIf { it > 0 },
                seriesName = if (o.isNull("series")) null else o.optString("series").takeIf { it.isNotBlank() },
                progress = o.optLong("dur").takeIf { it > 0 }?.let { (o.optLong("pos").toFloat() / it).coerceIn(0f, 1f) },
                runtimeMinutes = (o.optLong("dur") / 60_000L).toInt().takeIf { it > 0 },
                seriesId = if (o.isNull("seriesId")) null else o.optString("seriesId").takeIf { it.isNotBlank() }
            )
            entries += Triple(o.optLong("at"), item, o.optLong("pos"))
        }
        return entries.sortedByDescending { it.first }.map { it.second to it.third }
    }

    /** The saved resume points. Plain entries from older versions are masked and rewritten once. */
    private fun resumeObject(): JSONObject {
        val all = try {
            JSONObject(prefs.getString(K_RESUME, "{}") ?: "{}")
        } catch (e: Exception) {
            return JSONObject()
        }
        var changed = false
        val keys = all.keys()
        while (keys.hasNext()) {
            val o = all.optJSONObject(keys.next()) ?: continue
            for (field in listOf("url", "poster")) {
                val value = o.optString(field)
                val masked = maskStreamUrl(value)
                if (masked != value) {
                    o.put(field, masked)
                    changed = true
                }
            }
        }
        if (changed) prefs.edit().putString(K_RESUME, all.toString()).apply()
        return all
    }

    private fun resumeKey(item: VodItem): String = "${item.source}:${item.id}"

    /** Null until this profile has chosen its own hidden list. The shared settings are the fallback. */
    fun hiddenFilters(): String? =
        try { if (prefs.contains(K_HIDDEN)) prefs.getString(K_HIDDEN, "") else null } catch (e: Exception) { null }

    fun setHiddenFilters(value: String) {
        prefs.edit().putString(K_HIDDEN, value).apply()
    }

    fun reminders(): List<Reminder> {
        val all = try {
            val arr = JSONArray(prefs.getString(K_REMIND, "[]") ?: "[]")
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                Reminder(o.optString("channel"), o.optString("name"), o.optString("title"), o.optLong("start"))
            }
        } catch (e: Exception) {
            emptyList()
        }
        return pruneReminders(all, System.currentTimeMillis())
    }

    /** Adds or removes a reminder. Returns true only when the reminder was stored. */
    fun toggleReminder(reminder: Reminder): Boolean {
        val (list, stored) = toggleReminderIn(reminders(), reminder, System.currentTimeMillis())
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("channel", it.channelId).put("name", it.channelName).put("title", it.title).put("start", it.start))
        }
        prefs.edit().putString(K_REMIND, arr.toString()).apply()
        return stored
    }

    private companion object {
        const val K_FAV = "favorites"
        const val K_RECENT = "recent_channels"
        const val K_RESUME = "resume_points"
        const val K_HIDDEN = "hidden_filters"
        const val K_REMIND = "reminders"
        const val MAX_RECENT = 20
    }
}
