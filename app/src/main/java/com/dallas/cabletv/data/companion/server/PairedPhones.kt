package com.dallas.cabletv.data.companion.server

import org.json.JSONArray
import org.json.JSONObject

/** A phone this TV paired with. [fingerprint] is the SHA-256 of its certificate, in hex. */
data class PairedPhone(val fingerprint: String, val name: String, val pairedAt: Long)

/** Reads, writes and edits the saved list of paired phones. Plain Kotlin, so tests run it anywhere. */
object PairedPhones {
    /** The list as stored text. */
    fun encode(phones: List<PairedPhone>): String {
        val arr = JSONArray()
        phones.forEach {
            arr.put(JSONObject().put("fingerprint", it.fingerprint).put("name", it.name).put("paired_at", it.pairedAt))
        }
        return arr.toString()
    }

    /** The list from stored text. Anything unreadable gives an empty list, so a bad file never blocks the app. */
    fun decode(text: String?): List<PairedPhone> {
        if (text.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(text)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val fp = o.optString("fingerprint").lowercase()
                if (fp.isEmpty()) return@mapNotNull null
                PairedPhone(fp, o.optString("name"), o.optLong("paired_at"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** [list] with [phone] added. A phone that paired before is replaced, so it appears once. */
    fun with(list: List<PairedPhone>, phone: PairedPhone): List<PairedPhone> =
        list.filterNot { it.fingerprint == phone.fingerprint } + phone

    /** [list] without the phone that has [fingerprint]. */
    fun without(list: List<PairedPhone>, fingerprint: String): List<PairedPhone> =
        list.filterNot { it.fingerprint == fingerprint.lowercase() }

    fun contains(list: List<PairedPhone>, fingerprint: String): Boolean =
        list.any { it.fingerprint == fingerprint.lowercase() }
}
