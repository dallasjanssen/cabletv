package com.dallas.cabletv.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Stores provider credentials, tokens, and the parental-lock PIN encrypted on the device.
 * Nothing secret lives in source code or in plain preferences.
 */
class SettingsStore(context: Context) {

    /** True when the saved settings could not be opened and were wiped, so the app starts clean. */
    val wasReset: Boolean

    private val prefs: SharedPreferences

    init {
        val appContext = context.applicationContext
        var reset = false
        prefs = try {
            openPrefs(appContext)
        } catch (e: Exception) {
            // A corrupt keyset or a lost Keystore key. Start over rather than crash at launch.
            reset = true
            runCatching { appContext.deleteSharedPreferences(PREFS_NAME) }
            runCatching {
                val store = KeyStore.getInstance("AndroidKeyStore")
                store.load(null)
                store.deleteEntry(MasterKey.DEFAULT_MASTER_KEY_ALIAS)
            }
            openPrefs(appContext)
        }
        wasReset = reset
    }

    private fun openPrefs(context: Context): SharedPreferences = EncryptedSharedPreferences.create(
        context,
        PREFS_NAME,
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun load(): Settings = Settings(
        xtreamServer = prefs.getString(K_XS, "").orEmpty(),
        xtreamUser = prefs.getString(K_XU, "").orEmpty(),
        xtreamPass = prefs.getString(K_XP, "").orEmpty(),
        m3uUrl = prefs.getString(K_M3U, "").orEmpty(),
        epgUrl = prefs.getString(K_EPG, "").orEmpty(),
        jellyfinServer = prefs.getString(K_JS, "").orEmpty(),
        jellyfinToken = prefs.getString(K_JT, "").orEmpty(),
        jellyfinUserId = prefs.getString(K_JU, "").orEmpty(),
        hiddenFilters = prefs.getString(K_HIDDEN, "").orEmpty(),
        stripPrefixes = prefs.getBoolean(K_STRIP, false),
        lockedFilters = prefs.getString(K_LOCKED, "").orEmpty(),
        stalkerUrl = prefs.getString(K_STALKER_URL, "").orEmpty(),
        stalkerMac = prefs.getString(K_STALKER_MAC, "").orEmpty(),
        subscriptionExpiryMs = prefs.getLong(K_EXPIRY, 0L),
        homeAssistantWebhook = prefs.getString(K_HA_WEBHOOK, "").orEmpty()
    )

    /** A Jellyfin token that belongs to one profile. Null when that profile uses the household login. */
    fun profileJellyfin(profileId: String): Pair<String, String>? {
        val token = prefs.getString(jfTokenKey(profileId), null)?.takeIf { it.isNotBlank() } ?: return null
        val userId = prefs.getString(jfUserKey(profileId), null)?.takeIf { it.isNotBlank() } ?: return null
        return token to userId
    }

    fun saveProfileJellyfin(profileId: String, token: String, userId: String) {
        prefs.edit().putString(jfTokenKey(profileId), token).putString(jfUserKey(profileId), userId).apply()
    }

    fun clearProfileJellyfin(profileId: String) {
        prefs.edit().remove(jfTokenKey(profileId)).remove(jfUserKey(profileId)).apply()
    }

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
            .putString(K_HIDDEN, s.hiddenFilters)
            .putBoolean(K_STRIP, s.stripPrefixes)
            .putString(K_LOCKED, s.lockedFilters)
            .putString(K_STALKER_URL, s.stalkerUrl)
            .putString(K_STALKER_MAC, s.stalkerMac)
            .putLong(K_EXPIRY, s.subscriptionExpiryMs)
            .putString(K_HA_WEBHOOK, s.homeAssistantWebhook)
            .apply()
    }

    /** The channel that was on screen last, so the app can return to it at launch. */
    fun lastChannelId(): String? = prefs.getString(K_LAST_CHANNEL, null)?.takeIf { it.isNotBlank() }

    fun saveLastChannelId(id: String) {
        prefs.edit().putString(K_LAST_CHANNEL, id).apply()
    }

    /** The channel last watched under one profile. Falls back to the old shared value. */
    fun lastChannelId(profileId: String): String? =
        prefs.getString(lastChannelKey(profileId), null)?.takeIf { it.isNotBlank() } ?: lastChannelId()

    fun saveLastChannelId(profileId: String, id: String) {
        prefs.edit().putString(lastChannelKey(profileId), id).apply()
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

    /** Plain values from secure preferences, used to export a full device backup. */
    fun backupEntries(): Map<String, Any> =
        prefs.all.mapNotNull { (key, value) ->
            if (key.startsWith(RESERVED_PREFIX)) return@mapNotNull null
            when (value) {
                is String, is Boolean, is Int, is Long, is Float -> key to value
                is Set<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    val clean = (value as? Set<String>) ?: return@mapNotNull null
                    key to clean
                }
                else -> null
            }
        }.toMap()

    /** Replaces secure preferences from a backup. */
    fun restoreEntries(values: Map<String, Any>) {
        val edit = prefs.edit().clear()
        values.forEach { (key, value) ->
            if (key.startsWith(RESERVED_PREFIX)) return@forEach
            when (value) {
                is String -> edit.putString(key, value)
                is Boolean -> edit.putBoolean(key, value)
                is Int -> edit.putInt(key, value)
                is Long -> edit.putLong(key, value)
                is Float -> edit.putFloat(key, value)
                is Set<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    edit.putStringSet(key, value as? Set<String>)
                }
            }
        }
        edit.apply()
    }

    private fun hash(saltHex: String, pin: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest((saltHex + pin).toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val PREFS_NAME = "cabletv_secure_prefs"
        const val K_LAST_CHANNEL = "last_channel_id"
        const val K_XS = "xtream_server"
        const val K_XU = "xtream_user"
        const val K_XP = "xtream_pass"
        const val K_M3U = "m3u_url"
        const val K_EPG = "epg_url"
        const val K_JS = "jellyfin_server"
        const val K_JT = "jellyfin_token"
        const val K_JU = "jellyfin_user_id"
        const val K_HIDDEN = "hidden_filters"
        const val K_STRIP = "strip_prefixes"
        const val K_LOCKED = "locked_filters"
        const val K_STALKER_URL = "stalker_url"
        const val K_STALKER_MAC = "stalker_mac"
        const val K_EXPIRY = "subscription_expiry"
        const val K_HA_WEBHOOK = "home_assistant_webhook"
        const val K_PIN = "parental_pin"
        private const val RESERVED_PREFIX = "__androidx_security_crypto_"

        private fun jfTokenKey(profileId: String) = "jf_token_$profileId"
        private fun lastChannelKey(profileId: String) = "last_channel_$profileId"
        private fun jfUserKey(profileId: String) = "jf_user_$profileId"
    }
}
