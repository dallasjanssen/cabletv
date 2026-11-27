package com.dallas.cabletv.data

import android.content.Context

/**
 * How often each channel has been watched. Ordinary preferences, not the encrypted login store.
 * A blank id is ignored so a missing channel cannot create a row.
 */
object WatchStats {
    private const val PREFS = "cabletv_watch_stats"
    private val lock = Any()

    fun record(context: Context, channelId: String) {
        if (channelId.isBlank()) return
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        synchronized(lock) {
            val next = runCatching { prefs.getInt(channelId, 0) }.getOrDefault(0) + 1
            prefs.edit().putInt(channelId, next).apply()
        }
    }

    fun counts(context: Context): Map<String, Int> {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val out = linkedMapOf<String, Int>()
        for ((key, value) in prefs.all) {
            val n = value as? Int ?: continue
            if (key.isNotBlank()) out[key] = n
        }
        return out
    }
}
