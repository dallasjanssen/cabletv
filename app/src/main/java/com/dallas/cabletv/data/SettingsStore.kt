package com.dallas.cabletv.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Stores provider credentials, tokens, and the parental-lock PIN encrypted on the device.
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
        jellyfinUserId = prefs.getString(K_JU, "").orEmpty(),
        hiddenFilters = prefs.getString(K_HIDDEN, "").orEmpty(),
        stripPrefixes = prefs.getBoolean(K_STRIP, false)
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
            .putString(K_HIDDEN, s.hiddenFilters)
            .putBoolean(K_STRIP, s.stripPrefixes)
            .apply()
    }

    /** The channel that was on screen last, so the app can return to it at launch. */
    fun lastChannelId(): String? = prefs.getString(K_LAST_CHANNEL, null)?.takeIf { it.isNotBlank() }

    fun saveLastChannelId(id: String) {
        prefs.edit().putString(K_LAST_CHANNEL, id).apply()
    }

    fun hasPin(): Boolean = prefs.contains(K_PIN)

    /** Stores a new PIN as a salted hash. Passing an empty string removes the lock. */
    fun setPin(pin: String) {
        if (pin.isEmpty()) {
            prefs.edit().remove(K_PIN).apply()
            return
        }
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val saltHex = salt.joinToString("") { "%02x".format(it) }
        prefs.edit().putString(K_PIN, "$saltHex:${hash(saltHex, pin)}").apply()
    }

    /** True when the PIN matches the stored one. Always false when no PIN is set. */
    fun checkPin(pin: String): Boolean {
        val stored = prefs.getString(K_PIN, null) ?: return false
        val saltHex = stored.substringBefore(':')
        return stored.substringAfter(':') == hash(saltHex, pin)
    }

    private fun hash(saltHex: String, pin: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest((saltHex + pin).toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
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
        const val K_HIDDEN = "hidden_filters"
        const val K_STRIP = "strip_prefixes"
        const val K_PIN = "parental_pin"
    }
}
