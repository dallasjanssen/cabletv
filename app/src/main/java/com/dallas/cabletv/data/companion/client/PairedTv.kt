package com.dallas.cabletv.data.companion.client

import com.dallas.cabletv.data.companion.CompanionProtocol
import org.json.JSONArray
import org.json.JSONObject

/**
 * A TV this phone has paired with. [fingerprint] is the hex SHA-256 of the certificate the TV
 * showed during pairing, and every later connection must show the same one. [host] and [port] are
 * where the TV was last seen, and change when discovery finds it somewhere new.
 */
data class PairedTv(
    val id: String,
    val name: String,
    val fingerprint: String,
    val host: String,
    val port: Int,
    /** True once the Android TV remote (power and volume) is paired with this TV. */
    val atvPaired: Boolean = false
) {
    /** The fingerprint as bytes, ready to compare with a certificate. */
    fun fingerprintBytes(): ByteArray = CompanionProtocol.fromHex(fingerprint)
}

/** How the paired TV list is written to and read from storage, and the changes made to it. */
object PairedTvJson {
    /** The list as stored: a JSON array of {id, name, fingerprint, host, port, atv_paired}. */
    fun encode(tvs: List<PairedTv>): String {
        val array = JSONArray()
        for (tv in tvs) {
            array.put(
                JSONObject()
                    .put("id", tv.id)
                    .put("name", tv.name)
                    .put("fingerprint", tv.fingerprint)
                    .put("host", tv.host)
                    .put("port", tv.port)
                    .put("atv_paired", tv.atvPaired)
            )
        }
        return array.toString()
    }

    /** Reads the stored list. Entries that are incomplete are skipped, and bad text gives an empty list. */
    fun decode(text: String?): List<PairedTv> {
        if (text.isNullOrBlank()) return emptyList()
        val array = try {
            JSONArray(text)
        } catch (e: Exception) {
            return emptyList()
        }
        val out = ArrayList<PairedTv>()
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val id = o.optString("id")
            val fingerprint = o.optString("fingerprint")
            val host = o.optString("host")
            val port = o.optInt("port", CompanionProtocol.DEFAULT_PORT)
            if (id.isBlank() || fingerprint.isBlank() || host.isBlank()) continue
            if (out.any { it.id == id }) continue
            out.add(
                PairedTv(
                    id = id,
                    name = o.optString("name").ifBlank { "TV" },
                    fingerprint = fingerprint,
                    host = host,
                    port = port,
                    atvPaired = o.optBoolean("atv_paired", false)
                )
            )
        }
        return out
    }

    /** Adds [tv], or replaces the TV with the same id and keeps its place in the list. */
    fun upsert(tvs: List<PairedTv>, tv: PairedTv): List<PairedTv> =
        if (tvs.any { it.id == tv.id }) tvs.map { if (it.id == tv.id) tv else it } else tvs + tv

    fun remove(tvs: List<PairedTv>, id: String): List<PairedTv> = tvs.filter { it.id != id }

    /** Moves a paired TV to a new address. The list is returned as it was when nothing changes. */
    fun withAddress(tvs: List<PairedTv>, id: String, host: String, port: Int): List<PairedTv> {
        val current = tvs.firstOrNull { it.id == id } ?: return tvs
        if (current.host == host && current.port == port) return tvs
        return upsert(tvs, current.copy(host = host, port = port))
    }

    fun withAtvPaired(tvs: List<PairedTv>, id: String, paired: Boolean): List<PairedTv> {
        val current = tvs.firstOrNull { it.id == id } ?: return tvs
        if (current.atvPaired == paired) return tvs
        return upsert(tvs, current.copy(atvPaired = paired))
    }
}
