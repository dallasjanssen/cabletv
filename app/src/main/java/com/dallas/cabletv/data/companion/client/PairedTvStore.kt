package com.dallas.cabletv.data.companion.client

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The TVs this phone has paired with, kept in its own preferences file. The file is left out of
 * backups on purpose: a pairing belongs to this phone's keystore key, which a backup cannot carry.
 * The JSON mapping and the list changes are in [PairedTvJson].
 */
class PairedTvStore(context: Context) {
    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val _tvs = MutableStateFlow(PairedTvJson.decode(prefs.getString(KEY, null)))

    /** The paired TVs, in the order they were paired. */
    val tvs: StateFlow<List<PairedTv>> = _tvs

    fun find(id: String): PairedTv? = _tvs.value.firstOrNull { it.id == id }

    fun save(tv: PairedTv) = change { PairedTvJson.upsert(it, tv) }

    fun remove(id: String) = change { PairedTvJson.remove(it, id) }

    /** Remembers where a paired TV was found now. Does nothing when the address is the same. */
    fun noteAddress(id: String, host: String, port: Int) = change { PairedTvJson.withAddress(it, id, host, port) }

    fun setAtvPaired(id: String, paired: Boolean) = change { PairedTvJson.withAtvPaired(it, id, paired) }

    @Synchronized
    private fun change(update: (List<PairedTv>) -> List<PairedTv>) {
        val before = _tvs.value
        val after = update(before)
        if (after == before) return
        prefs.edit().putString(KEY, PairedTvJson.encode(after)).apply()
        _tvs.value = after
    }

    companion object {
        const val PREFS_NAME = "cabletv_companion_tvs"
        private const val KEY = "tvs"
    }
}
