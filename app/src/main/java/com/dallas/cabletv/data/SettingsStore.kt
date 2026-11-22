package com.dallas.cabletv.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Stores provider credentials and tokens encrypted on the device.
 * Nothing secret lives in source code or in plain preferences.
 */
class SettingsStore(context: Context) {

    private val prefs: SharedPreferences = openEncrypted(context)

    private fun openEncrypted(context: Context): SharedPreferences {
        fun create() = EncryptedSharedPreferences.create(
            context,
            "cabletv_secure_prefs",
            MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
        return try {
            create()
        } catch (e: Exception) {
            // Some devices end up with a keystore that no longer opens the old file.
            // Credentials are then lost and must be entered again, which is safer than crashing.
            context.deleteSharedPreferences("cabletv_secure_prefs")
            create()
        }
    }

    fun load(): Settings = Settings(
        xtreamServer = prefs.getString(K_XS, "").orEmpty(),
        xtreamUser = prefs.getString(K_XU, "").orEmpty(),
        xtreamPass = prefs.getString(K_XP, "").orEmpty(),
        m3uUrl = prefs.getString(K_M3U, "").orEmpty(),
        epgUrl = prefs.getString(K_EPG, "").orEmpty(),
        jellyfinServer = prefs.getString(K_JS, "").orEmpty(),
        jellyfinToken = prefs.getString(K_JT, "").orEmpty(),
        jellyfinUserId = prefs.getString(K_JU, "").orEmpty(),
        jellyfinTranscodeFallback = prefs.getBoolean(K_JTF, true)
    )

    fun save(s: Settings) {
        prefs.edit()
            .putString(K_XS, s.xtreamServer)
            .putString(K_XU, s.xtreamUser)
            .putString(K_XP, s.xtreamPass)
            .putString(K_M3U, s.m3uUrl)
            .putString(K_EPG, s.epgUrl)
            .putString(K_JS, s.jellyfinServer)
            .putString(K_JT, s.jellyfinToken)
            .putString(K_JU, s.jellyfinUserId)
            .putBoolean(K_JTF, s.jellyfinTranscodeFallback)
            .apply()
    }

    private companion object {
        const val K_XS = "xtream_server"
        const val K_XU = "xtream_user"
        const val K_XP = "xtream_pass"
        const val K_M3U = "m3u_url"
        const val K_EPG = "epg_url"
        const val K_JS = "jellyfin_server"
        const val K_JT = "jellyfin_token"
        const val K_JU = "jellyfin_user_id"
        const val K_JTF = "jellyfin_transcode_fallback"
    }
}

/**
 * Non-secret UI state: favorites, last channel, and Jellyfin resume hints.
 * Kept in plain preferences because none of it is sensitive.
 */
class AppStateStore(context: Context) {

    private val prefs = context.getSharedPreferences("cabletv_app_state", Context.MODE_PRIVATE)

    fun favorites(): Set<String> = prefs.getStringSet(K_FAV, emptySet()).orEmpty().toSet()

    fun setFavorites(ids: Set<String>) {
        prefs.edit().putStringSet(K_FAV, ids).apply()
    }

    /** Channel id of the channel that was on before the current one. */
    fun lastChannelId(): String? = prefs.getString(K_LAST, null)

    fun setLastChannelId(id: String?) {
        prefs.edit().putString(K_LAST, id).apply()
    }

    /** Channel id that was playing when the app last ran, so Live TV can pick up there. */
    fun currentChannelId(): String? = prefs.getString(K_CUR, null)

    fun setCurrentChannelId(id: String?) {
        prefs.edit().putString(K_CUR, id).apply()
    }

    /** A random id created once per install. Jellyfin uses it to tell devices apart. */
    fun deviceId(): String {
        prefs.getString(K_DEVICE, null)?.let { return it }
        val id = java.util.UUID.randomUUID().toString()
        prefs.edit().putString(K_DEVICE, id).apply()
        return id
    }

    private companion object {
        const val K_FAV = "favorites"
        const val K_LAST = "last_channel"
        const val K_CUR = "current_channel"
        const val K_DEVICE = "device_id"
    }
}
