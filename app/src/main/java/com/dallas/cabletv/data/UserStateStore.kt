package com.dallas.cabletv.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Favorites, recently watched channels and on-demand resume points.
 * None of this is secret, so it lives in ordinary preferences.
 */
class UserStateStore(context: Context) {

    private val prefs = context.getSharedPreferences("cabletv_user_state", Context.MODE_PRIVATE)

    fun favorites(): Set<String> = prefs.getStringSet(K_FAV, emptySet())?.toSet() ?: emptySet()

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
        val arr = JSONArray(prefs.getString(K_RECENT, "[]") ?: "[]")
        return (0 until arr.length()).map { arr.optString(it) }
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
                    .put("poster", item.posterUrl)
                    .put("url", item.streamUrl)
                    .put("type", item.type)
                    .put("source", item.source)
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
                posterUrl = o.optString("poster"),
                streamUrl = o.optString("url"),
                type = o.optString("type"),
                source = o.optString("source", "jellyfin")
            )
            entries += Triple(o.optLong("at"), item, o.optLong("pos"))
        }
        return entries.sortedByDescending { it.first }.map { it.second to it.third }
    }

    private fun resumeObject(): JSONObject =
        JSONObject(prefs.getString(K_RESUME, "{}") ?: "{}")

    private fun resumeKey(item: VodItem): String = "${item.source}:${item.id}"

    private companion object {
        const val K_FAV = "favorites"
        const val K_RECENT = "recent_channels"
        const val K_RESUME = "resume_points"
        const val MAX_RECENT = 20
    }
}
