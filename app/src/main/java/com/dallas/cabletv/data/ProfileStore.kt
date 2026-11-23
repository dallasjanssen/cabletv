package com.dallas.cabletv.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class Profile(val id: String, val name: String)

/**
 * The people who share this TV. Each profile has its own favorites, recent channels,
 * and resume points. Settings, hidden channels, and the parental lock are shared.
 */
class ProfileStore(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun profiles(): List<Profile> {
        return try {
            val arr = JSONArray(prefs.getString(K_LIST, null) ?: defaultList())
            val list = (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Profile(id = o.getString("id"), name = o.getString("name"))
            }
            list.ifEmpty { defaultProfiles() }
        } catch (e: Exception) {
            defaultProfiles()
        }
    }

    private fun defaultProfiles(): List<Profile> = listOf(Profile(DEFAULT_ID, "Main"))

    fun active(): String {
        val id = prefs.getString(K_ACTIVE, DEFAULT_ID) ?: DEFAULT_ID
        return if (profiles().any { it.id == id }) id else DEFAULT_ID
    }

    fun setActive(id: String) {
        prefs.edit().putString(K_ACTIVE, id).apply()
    }

    /** Adds a profile and returns it. Blank names are rejected. */
    fun add(name: String): Profile? {
        val clean = name.trim()
        if (clean.isEmpty()) return null
        val profile = Profile(id = UUID.randomUUID().toString().take(8), name = clean)
        save(profiles() + profile)
        return profile
    }

    /** Removes a profile from the list. The default profile cannot be removed. */
    fun remove(id: String) {
        if (id == DEFAULT_ID) return
        save(profiles().filter { it.id != id })
        if (active() == id) setActive(DEFAULT_ID)
        runCatching { appContext.deleteSharedPreferences(userStatePrefsName(id)) }
    }

    /** Plain values from profile preferences, used for backup and restore. */
    fun backupEntries(): Map<String, Any> =
        prefs.all.mapNotNull { (key, value) ->
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

    /** Replaces profile preferences from a backup. */
    fun restoreEntries(values: Map<String, Any>) {
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

    private fun save(list: List<Profile>) {
        val arr = JSONArray()
        list.forEach { p -> arr.put(JSONObject().put("id", p.id).put("name", p.name)) }
        prefs.edit().putString(K_LIST, arr.toString()).apply()
    }

    private fun defaultList(): String =
        JSONArray().put(JSONObject().put("id", DEFAULT_ID).put("name", "Main")).toString()

    companion object {
        const val DEFAULT_ID = "default"
        const val PREFS_NAME = "cabletv_profiles"
        private const val K_LIST = "profile_list"
        private const val K_ACTIVE = "active_profile"
    }
}
