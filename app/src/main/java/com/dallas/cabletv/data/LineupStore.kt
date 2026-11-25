package com.dallas.cabletv.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Counts every change to the saved lineup, from the screen, a sync or a backup restore. The channel
 * list watches it and reloads, so the new numbers show without a restart.
 */
object LineupChanges {
    private val counter = MutableStateFlow(0)
    val revision: StateFlow<Int> = counter.asStateFlow()

    internal fun bump() {
        counter.value = counter.value + 1
    }
}

/**
 * The saved lineup on this TV. It is encrypted like the settings, because it holds the OpenRouter
 * key. Backup and sync read and write it through [backupEntries], [restoreEntries] and [lineupToJson].
 */
class LineupStore(context: Context) {
    private val prefs: SharedPreferences? = try {
        open(context.applicationContext)
    } catch (e: Exception) {
        null
    }

    /** The saved lineup, or an empty one when none is saved or the store cannot be opened. */
    fun load(): LineupDoc = lineupFromJson(prefs?.getString(K_DOC, null))

    fun save(doc: LineupDoc) {
        val p = prefs ?: error("The lineup could not be saved on this device.")
        p.edit().putString(K_DOC, lineupToJson(doc).toString()).apply()
        LineupChanges.bump()
    }

    /** The lineup as one backup value, so backup handles it like any other saved preference. */
    fun backupEntries(): Map<String, Any> =
        prefs?.getString(K_DOC, null)?.let { mapOf(K_DOC to it) }.orEmpty()

    /** Replaces the saved lineup with one from a backup. */
    fun restoreEntries(values: Map<String, Any>) {
        val p = prefs ?: return
        val text = values[K_DOC] as? String
        p.edit().apply {
            if (text == null) remove(K_DOC) else putString(K_DOC, text)
        }.apply()
        LineupChanges.bump()
    }

    private fun open(context: Context): SharedPreferences = EncryptedSharedPreferences.create(
        context,
        PREFS_NAME,
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    companion object {
        const val PREFS_NAME = "cabletv_lineup_secure"
        private const val K_DOC = "doc"
    }
}

/** The lineup as JSON. Used for storage, backup and sync, so all three read the same shape. */
fun lineupToJson(doc: LineupDoc): JSONObject {
    val lineup = JSONArray()
    doc.lineup.forEach { c ->
        lineup.put(
            JSONObject()
                .put("number", c.number)
                .put("alt", c.altNumber ?: JSONObject.NULL)
                .put("name", c.name)
                .put("genre", c.genre)
                .put("pack", c.pack)
        )
    }
    val assignments = JSONObject()
    doc.assignments.forEach { (id, a) ->
        assignments.put(
            id,
            JSONObject()
                .put("name", a.providerName)
                .put("number", a.number ?: JSONObject.NULL)
                .put("genre", a.genre)
                .put("source", a.source)
                .put("confidence", a.confidence)
        )
    }
    val overrides = JSONObject()
    doc.overrides.forEach { (id, number) -> overrides.put(id, number) }
    val blocks = JSONObject()
    doc.genreBlocks.forEach { (genre, start) -> blocks.put(genre, start) }
    return JSONObject()
        .put("revision", doc.revision)
        .put("builtAtMs", doc.builtAtMs)
        .put("lineup", lineup)
        .put("assignments", assignments)
        .put("overrides", overrides)
        .put("genreBlocks", blocks)
        .put("openRouterKey", doc.openRouterKey)
        .put("openRouterModel", doc.openRouterModel)
}

/** Reads the JSON from [lineupToJson]. Missing or broken data gives an empty lineup, never a crash. */
fun lineupFromJson(text: String?): LineupDoc {
    if (text.isNullOrBlank()) return LineupDoc()
    return try {
        val o = JSONObject(text)
        val lineupArr = o.optJSONArray("lineup") ?: JSONArray()
        val lineup = (0 until lineupArr.length()).mapNotNull { i ->
            val c = lineupArr.optJSONObject(i) ?: return@mapNotNull null
            LineupChannel(
                number = c.optInt("number", 0),
                altNumber = c.optInt("alt", 0).takeIf { it > 0 },
                name = c.optString("name"),
                genre = c.optString("genre", "Other"),
                pack = c.optString("pack")
            )
        }.filter { it.number > 0 && it.name.isNotBlank() }

        val ao = o.optJSONObject("assignments") ?: JSONObject()
        val assignments = ao.keys().asSequence().mapNotNull { id ->
            val a = ao.optJSONObject(id) ?: return@mapNotNull null
            id to LineupAssignment(
                providerId = id,
                providerName = a.optString("name"),
                number = a.optInt("number", 0).takeIf { it > 0 },
                genre = a.optString("genre", "Other"),
                source = a.optString("source", "model"),
                confidence = a.optDouble("confidence", 0.0)
            )
        }.toMap()

        val oo = o.optJSONObject("overrides") ?: JSONObject()
        val overrides = oo.keys().asSequence().mapNotNull { id ->
            oo.optInt(id, 0).takeIf { it > 0 }?.let { id to it }
        }.toMap()

        val bo = o.optJSONObject("genreBlocks")
        val blocks = if (bo == null) DEFAULT_GENRE_BLOCKS else bo.keys().asSequence().mapNotNull { genre ->
            bo.optInt(genre, 0).takeIf { it > 0 }?.let { genre to it }
        }.toMap()

        LineupDoc(
            revision = o.optInt("revision", 0),
            builtAtMs = o.optLong("builtAtMs", 0L),
            lineup = lineup,
            assignments = assignments,
            overrides = overrides,
            genreBlocks = blocks,
            openRouterKey = o.optString("openRouterKey"),
            openRouterModel = o.optString("openRouterModel")
        )
    } catch (e: Exception) {
        LineupDoc()
    }
}

/**
 * What the phone shows about the lineup: sizes, whether the key and model are set, and the build's
 * progress. The key itself is never sent back, only whether one is saved.
 */
fun lineupSummaryJson(doc: LineupDoc, build: LineupBuildStatus.Snapshot): JSONObject {
    val state = when (build.state) {
        LineupBuildStatus.State.IDLE -> "idle"
        LineupBuildStatus.State.RUNNING -> "running"
        LineupBuildStatus.State.DONE -> "done"
        LineupBuildStatus.State.FAILED -> "failed"
    }
    return JSONObject()
        .put("lineup_channels", doc.lineup.size)
        .put("placed_by_model", doc.assignments.values.count { it.number != null })
        .put("in_genre_blocks", doc.assignments.values.count { it.number == null })
        .put("overrides", doc.overrides.size)
        .put("revision", doc.revision)
        .put("built_at_ms", doc.builtAtMs)
        .put("key_set", doc.openRouterKey.isNotBlank())
        .put("model", doc.openRouterModel)
        .put(
            "build",
            JSONObject()
                .put("state", state)
                .put("message", build.message)
                .put("done", build.done)
                .put("total", build.total)
        )
}
