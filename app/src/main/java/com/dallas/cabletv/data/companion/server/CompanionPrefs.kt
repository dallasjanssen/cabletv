package com.dallas.cabletv.data.companion.server

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

/**
 * What this TV remembers for the phone companion: its install id and the phones it paired with.
 * They live in their own preferences file, which BackupStore does not export and the app does not
 * allow Android to back up, so a pairing belongs to this one TV. Make one and share it, because the
 * list of phones is observed by Settings and read by the server on every message.
 */
class CompanionPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val phones = MutableStateFlow(PairedPhones.decode(prefs.getString(K_PAIRED, null)))

    /** The paired phones, newest last. */
    val pairedPhones: StateFlow<List<PairedPhone>> get() = phones

    /** A random id made once, so a phone can tell this TV from another with the same name. */
    val tvId: String
        @Synchronized get() {
            prefs.getString(K_TV_ID, null)?.takeIf { it.isNotBlank() }?.let { return it }
            val id = UUID.randomUUID().toString()
            prefs.edit().putString(K_TV_ID, id).apply()
            return id
        }

    fun isPaired(fingerprint: String): Boolean = PairedPhones.contains(phones.value, fingerprint)

    @Synchronized
    fun addPaired(phone: PairedPhone) {
        save(PairedPhones.with(phones.value, phone))
    }

    @Synchronized
    fun removePaired(fingerprint: String) {
        save(PairedPhones.without(phones.value, fingerprint))
    }

    private fun save(list: List<PairedPhone>) {
        prefs.edit().putString(K_PAIRED, PairedPhones.encode(list)).apply()
        phones.value = list
    }

    companion object {
        const val PREFS_NAME = "cabletv_companion"
        private const val K_TV_ID = "tv_id"
        private const val K_PAIRED = "paired_phones"
    }
}
