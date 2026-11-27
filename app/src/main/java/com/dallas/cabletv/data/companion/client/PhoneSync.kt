package com.dallas.cabletv.data.companion.client

import org.json.JSONObject

/**
 * The id the phone's own sync copy is stored under. No TV is ever given this id, so it never
 * clashes with a paired TV's copy.
 */
const val PHONE_SYNC_ID = "this-phone"

/** The section keys present in a sync file, in the order the file holds them. */
fun syncSectionKeys(data: JSONObject): List<String> =
    data.optJSONObject("sections")?.keys()?.asSequence()?.toList().orEmpty()

/** A sync file holding only the sections in [keys] that [data] has. */
fun syncFileWithSections(data: JSONObject, keys: List<String>): JSONObject {
    val all = data.optJSONObject("sections") ?: JSONObject()
    val picked = JSONObject()
    keys.forEach { key -> if (all.has(key)) picked.put(key, all.get(key)) }
    return syncFile(data, picked)
}

/**
 * The phone's copy after [fresh] has been exported from a TV. Sections in [fresh] replace the same
 * sections in [old]; sections [fresh] does not have keep their older values.
 */
fun mergedPhoneCopy(old: JSONObject?, fresh: JSONObject): JSONObject {
    val sections = JSONObject()
    old?.optJSONObject("sections")?.let { s -> s.keys().asSequence().forEach { sections.put(it, s.get(it)) } }
    fresh.optJSONObject("sections")?.let { s -> s.keys().asSequence().forEach { sections.put(it, s.get(it)) } }
    return syncFile(fresh, sections)
}

private fun syncFile(from: JSONObject, sections: JSONObject): JSONObject =
    JSONObject()
        .put("format", from.optInt("format", 1))
        .put("source_tv", from.optString("source_tv"))
        .put("sections", sections)
