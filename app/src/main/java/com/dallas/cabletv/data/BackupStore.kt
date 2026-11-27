package com.dallas.cabletv.data

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Full settings backup and restore, including encrypted logins, profile setup,
 * and per-profile state such as favorites and resume points.
 */
class BackupStore(
    context: Context,
    private val settingsStore: SettingsStore,
    private val profileStore: ProfileStore,
    private val lineupStore: LineupStore
) {
    private val appContext = context.applicationContext

    fun backupJson(): String = exportJson()

    fun writeBackup(uri: Uri) {
        val json = backupJson()
        val stream = appContext.contentResolver.openOutputStream(uri)
            ?: throw IllegalArgumentException("Could not write that file.")
        stream.bufferedWriter(Charsets.UTF_8).use { it.write(json) }
    }

    fun restoreBackup(uri: Uri) {
        val stream = appContext.contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException("Could not read that file.")
        val json = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        restoreBackup(json)
    }

    fun restoreBackup(json: String) {
        importJson(json)
    }

    /**
     * Downloads a backup and applies it. Only http and https are accepted, so a file
     * or other link cannot be fetched by mistake. The download uses a short-lived client.
     */
    fun restoreFromUrl(url: String) {
        val trimmed = url.trim()
        if (!trimmed.regionMatches(0, "http://", 0, 7, ignoreCase = true) &&
            !trimmed.regionMatches(0, "https://", 0, 8, ignoreCase = true)
        ) {
            throw IllegalArgumentException("The backup link needs to start with http:// or https://")
        }
        val json = try {
            downloadBackup(trimmed)
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: Exception) {
            throw IllegalArgumentException("Could not download that backup.")
        }
        restoreBackup(json)
    }

    private fun downloadBackup(url: String): String {
        val http = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
        val request = try {
            Request.Builder().url(url).header("User-Agent", "CableTV/1.0").build()
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("Could not download that backup.")
        }
        http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalArgumentException("Could not download that backup.")
            return resp.body?.string()?.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("Could not download that backup.")
        }
    }

    private fun exportJson(): String {
        val allPrefs = linkedMapOf<String, Map<String, Any>>()
        allPrefs[SettingsStore.PREFS_NAME] = settingsStore.backupEntries()
        allPrefs[ProfileStore.PREFS_NAME] = profileStore.backupEntries()
        allPrefs[LineupStore.PREFS_NAME] = lineupStore.backupEntries()
        allPrefs[TRACK_PREFS] = snapshotPrefs(TRACK_PREFS)
        profileIdsForBackup().forEach { id ->
            val name = userStatePrefsName(id)
            allPrefs[name] = snapshotPrefs(name)
        }

        val shared = JSONObject()
        allPrefs.forEach { (name, values) ->
            shared.put(name, encodePrefs(values))
        }

        return JSONObject()
            .put("schema", BACKUP_SCHEMA)
            .put("app", "cabletv")
            .put("createdAtMs", System.currentTimeMillis())
            .put("sharedPrefs", shared)
            .toString(2)
    }

    private fun importJson(json: String) {
        val root = runCatching { JSONObject(json) }.getOrElse {
            throw IllegalArgumentException("That file is not a valid backup.")
        }
        val schema = root.optInt("schema", -1)
        require(schema == BACKUP_SCHEMA) { "That backup version is not supported." }
        val prefJson = root.optJSONObject("sharedPrefs")
            ?: throw IllegalArgumentException("This backup is missing settings data.")

        val incoming = linkedMapOf<String, Map<String, Any>>()
        val prefNames = prefJson.keys()
        while (prefNames.hasNext()) {
            val name = prefNames.next()
            val values = prefJson.optJSONObject(name)
                ?: throw IllegalArgumentException("Backup data for $name is unreadable.")
            incoming[name] = decodePrefs(values)
        }

        val oldProfileIds = profileIdsForBackup()
        settingsStore.restoreEntries(incoming[SettingsStore.PREFS_NAME].orEmpty())
        profileStore.restoreEntries(incoming[ProfileStore.PREFS_NAME].orEmpty())
        // Older backups have no lineup. Leave the saved one alone when that is the case.
        incoming[LineupStore.PREFS_NAME]?.let { lineupStore.restoreEntries(it) }
        val newProfileIds = profileIdsForBackup()

        val knownOrdinary = linkedSetOf(TRACK_PREFS)
        knownOrdinary += oldProfileIds.map { userStatePrefsName(it) }
        knownOrdinary += newProfileIds.map { userStatePrefsName(it) }
        knownOrdinary += incoming.keys.filter { it == TRACK_PREFS || isUserStatePrefs(it) }
        knownOrdinary.forEach { name ->
            restorePrefs(appContext.getSharedPreferences(name, Context.MODE_PRIVATE), incoming[name].orEmpty())
        }

        incoming.forEach { (name, values) ->
            if (name == SettingsStore.PREFS_NAME || name == ProfileStore.PREFS_NAME || name == LineupStore.PREFS_NAME || name in knownOrdinary) return@forEach
            restorePrefs(appContext.getSharedPreferences(name, Context.MODE_PRIVATE), values)
        }
    }

    private fun profileIdsForBackup(): Set<String> {
        val ids = linkedSetOf(ProfileStore.DEFAULT_ID)
        ids += profileStore.profiles().map { it.id }.filter { it.isNotBlank() }
        return ids
    }

    private fun snapshotPrefs(name: String): Map<String, Any> =
        sanitize(appContext.getSharedPreferences(name, Context.MODE_PRIVATE).all)

    private fun sanitize(raw: Map<String, *>): Map<String, Any> =
        raw.mapNotNull { (key, value) ->
            when (value) {
                is String, is Boolean, is Int, is Long, is Float -> key to value
                is Set<*> -> {
                    val set = value.mapNotNull { it as? String }.toSet()
                    if (set.size == value.size) key to set else null
                }
                else -> null
            }
        }.toMap()

    private fun encodePrefs(values: Map<String, Any>): JSONObject {
        val out = JSONObject()
        values.forEach { (key, value) ->
            out.put(key, encodeValue(value))
        }
        return out
    }

    private fun decodePrefs(values: JSONObject): Map<String, Any> {
        val out = linkedMapOf<String, Any>()
        val keys = values.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val wrapped = values.optJSONObject(key)
                ?: throw IllegalArgumentException("Backup value '$key' is invalid.")
            out[key] = decodeValue(wrapped)
        }
        return out
    }

    private fun encodeValue(value: Any): JSONObject =
        when (value) {
            is String -> JSONObject().put("type", "string").put("value", value)
            is Boolean -> JSONObject().put("type", "boolean").put("value", value)
            is Int -> JSONObject().put("type", "int").put("value", value)
            is Long -> JSONObject().put("type", "long").put("value", value)
            is Float -> JSONObject().put("type", "float").put("value", value.toDouble())
            is Set<*> -> {
                val all = value.mapNotNull { it as? String }
                JSONObject().put("type", "string_set").put("value", JSONArray(all))
            }
            else -> throw IllegalArgumentException("Unsupported backup value type: ${value::class.java.simpleName}")
        }

    private fun decodeValue(value: JSONObject): Any {
        val type = value.optString("type")
        return when (type) {
            "string" -> value.optString("value", "")
            "boolean" -> value.getBoolean("value")
            "int" -> value.getInt("value")
            "long" -> value.getLong("value")
            "float" -> value.getDouble("value").toFloat()
            "string_set" -> {
                val arr = value.optJSONArray("value") ?: JSONArray()
                (0 until arr.length()).mapNotNull { arr.optString(it, null) }.toSet()
            }
            else -> throw IllegalArgumentException("Unsupported backup value type '$type'.")
        }
    }

    private fun restorePrefs(prefs: SharedPreferences, values: Map<String, Any>) {
        val edit = prefs.edit().clear()
        values.forEach { (key, value) ->
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
}

private const val BACKUP_SCHEMA = 1
private const val TRACK_PREFS = "cabletv_tracks"

private fun isUserStatePrefs(name: String): Boolean =
    name == "cabletv_user_state" || name.startsWith("cabletv_user_state_")
