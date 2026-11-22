package com.dallas.cabletv.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Stores provider credentials and tokens encrypted on the device.
 * Nothing secret lives in source code or in plain preferences.
 */
class SettingsStore(context: Context) {

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "cabletv_secure_prefs",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun load(): Settings = Settings(
        xtreamServer = prefs.getString(K_XS, "").orEmpty(),
        xtreamUser = prefs.getString(K_XU, "").orEmpty(),
        xtreamPass = prefs.getString(K_XP, "").orEmpty(),
        m3uUrl = prefs.getString(K_M3U, "").orEmpty(),
        jellyfinServer = prefs.getString(K_JS, "").orEmpty(),
        jellyfinToken = prefs.getString(K_JT, "").orEmpty(),
        jellyfinUserId = prefs.getString(K_JU, "").orEmpty()
    )

    fun save(s: Settings) {
        prefs.edit()
            .putString(K_XS, s.xtreamServer)
            .putString(K_XU, s.xtreamUser)
            .putString(K_XP, s.xtreamPass)
            .putString(K_M3U, s.m3uUrl)
            .putString(K_JS, s.jellyfinServer)
            .putString(K_JT, s.jellyfinToken)
            .putString(K_JU, s.jellyfinUserId)
            .apply()
    }

    /** The channel that was on screen last, so the app can return to it at launch. */
    fun lastChannelId(): String? = prefs.getString(K_LAST_CHANNEL, null)?.takeIf { it.isNotBlank() }

    fun saveLastChannelId(id: String) {
        prefs.edit().putString(K_LAST_CHANNEL, id).apply()
    }

    private companion object {
        const val K_LAST_CHANNEL = "last_channel_id"
        const val K_XS = "xtream_server"
        const val K_XU = "xtream_user"
        const val K_XP = "xtream_pass"
        const val K_M3U = "m3u_url"
        const val K_JS = "jellyfin_server"
        const val K_JT = "jellyfin_token"
        const val K_JU = "jellyfin_user_id"
    }
}
