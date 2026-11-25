package com.dallas.cabletv.data.companion.client

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONObject

/**
 * A copy of one TV's setup, kept on this phone. [data] is the sync file the TV exported, so it holds
 * logins, the parental PIN record and the lineup's OpenRouter key. It is encrypted for that reason.
 */
data class TvCopy(val tvId: String, val tvName: String, val savedAtMs: Long, val data: JSONObject)

/** The phone's saved copies, one per TV. Each copy is stored encrypted on the phone. */
class TvCopyStore(context: Context) {
    private val prefs: SharedPreferences? = try {
        open(context.applicationContext)
    } catch (e: Exception) {
        null
    }

    /** The copy saved for [tvId], or null when there is none or it cannot be read. */
    fun saved(tvId: String): TvCopy? {
        val text = prefs?.getString(keyFor(tvId), null) ?: return null
        return try {
            val o = JSONObject(text)
            TvCopy(
                tvId = tvId,
                tvName = o.optString("tv_name"),
                savedAtMs = o.optLong("saved_at_ms", 0L),
                data = o.getJSONObject("data")
            )
        } catch (e: Exception) {
            null
        }
    }

    fun save(copy: TvCopy) {
        val p = prefs ?: error("The copy could not be saved on this phone.")
        val text = JSONObject()
            .put("tv_name", copy.tvName)
            .put("saved_at_ms", copy.savedAtMs)
            .put("data", copy.data)
            .toString()
        p.edit().putString(keyFor(copy.tvId), text).apply()
    }

    fun remove(tvId: String) {
        prefs?.edit()?.remove(keyFor(tvId))?.apply()
    }

    private fun keyFor(tvId: String) = "copy_$tvId"

    private fun open(context: Context): SharedPreferences = EncryptedSharedPreferences.create(
        context,
        PREFS_NAME,
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    private companion object {
        const val PREFS_NAME = "cabletv_phone_copies_secure"
    }
}
